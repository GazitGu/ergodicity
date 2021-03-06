package com.ergodicity.backtest

import akka.actor.{Props, ActorSystem}
import akka.dispatch.{ExecutionContext, Promise, Await, Future}
import akka.event.Logging
import akka.pattern.ask
import akka.util.duration._
import com.ergodicity.backtest.Backtest.twitter2akka
import com.ergodicity.backtest.Backtest.BacktestReport
import com.ergodicity.backtest.Backtest.Config
import com.ergodicity.backtest.BacktestEngine.BacktestStubs
import com.ergodicity.backtest.BacktestEngine.GetStubs
import com.ergodicity.backtest.service.SessionsService.SessionAssigned
import com.ergodicity.backtest.service.{OrderBooksService, SessionContext, SessionsService}
import com.ergodicity.core._
import com.ergodicity.core.order.OrderBooksTracking.Snapshots
import com.ergodicity.core.order.OrdersSnapshotActor.OrdersSnapshot
import com.ergodicity.engine.Engine.{StartTrading, NotifyOnReady, StartEngine}
import com.ergodicity.engine.strategy.StrategiesFactory
import org.joda.time.Interval
import org.squeryl.PrimitiveTypeMode._
import com.ergodicity.marketdb.api.{MarketDbConfig, GetMarketDbConfig, MarketDbRep, MarketDbReq}
import com.twitter.finagle.Service
import com.ergodicity.marketdb.iteratee.MarketDbReader

object Backtest {

  case class Config(interval: Interval, securities: Seq[Isin])

  case class BacktestReport()

  implicit def twitter2akka[T](future: com.twitter.util.Future[T])(implicit executionContext: ExecutionContext) = new {
    def toAkkaFuture: akka.dispatch.Future[T] = {
      val promise = Promise[T]()
      future.onSuccess(res => promise.success(res))
      future.onFailure(err => promise.failure(err))
      promise
    }
  }

}

class Backtest(systemName: String, strategies: StrategiesFactory)(implicit config: Config, repository: MarketRepository, marketdb: Service[MarketDbReq, MarketDbRep]) {
  val system = ActorSystem(systemName)

  implicit val timeout = akka.util.Timeout(15.second)
  implicit val executionContext = system.dispatcher

  val log = Logging(system, classOf[Backtest])

  val engine = system.actorOf(Props(new BacktestEngine(system) {
    def strategies = Backtest.this.strategies
  }), "Engine")

  val stubs = Await.result((engine ? GetStubs).mapTo[BacktestStubs], 1.second)

  implicit val sessions = new SessionsService(stubs.futInfo, stubs.optInfo)

  def apply(): Future[BacktestReport] = (marketdb(GetMarketDbConfig) map {
    case mdb@MarketDbConfig(connection) =>
      implicit val marketDbReader = new MarketDbReader(connection)

      log.info("Execute backtesting with MarketDb {}", mdb)
      log.info(" - interval = {}", config.interval)
      log.info(" - securities = {}", config.securities)
      log.info(" - strategies = {}", strategies.strategies.map(_.id))

      // Start engine
      engine ! StartEngine

      val contents = inTransaction(repository.scan(config.interval).map {
        case (session, futures, options) => (session, futures.filter(c => config.securities.exists(_.isin == c.isin)), options.filter(c => config.securities.exists(_.isin == c.isin)))
      })

      if (contents.size < 1) {
        throw new IllegalArgumentException("No sessions available for given interval")
      }

      // Initialize first session to start engine
      val (s, f, o) = contents.head
      implicit val context = SessionContext(s, f, o)
      log.debug("Session: {}", s)
      log.debug("Contents: futures = {}, options = {}", f.size, o.size)

      val assigned = sessions.assign(s, f, o)

      // Dispatch empty order books snapshots
      val orderBooks = new OrderBooksService(stubs.ordLog, stubs.futOrderBook, stubs.optOrderBook)
      orderBooks.dispatchSnapshots(Snapshots(OrdersSnapshot.empty, OrdersSnapshot.empty))

      // Start engine (services & strategies)
      engine ! StartEngine
      Await.result((engine ? NotifyOnReady), 15.seconds)

      // Start trading
      engine ! StartTrading

      // Process first session
      processSession(assigned)

      // Process remaining sessions
      for ((session, futures, options) <- contents.tail) {
        implicit val context = SessionContext(session, futures, options)
        log.debug("Session: {}", session)
        log.debug("Contents: futures = {}, options = {}", futures.size, options.size)

        val assigned = sessions.assign(session, futures, options)
        processSession(assigned)
      }

      BacktestReport()

    case answer => throw new RuntimeException("Unexpected service answer: "+answer)
  }).toAkkaFuture

  private def processSession(assigned: SessionAssigned)(implicit context: SessionContext) {
    val eveningSession = assigned.start()

    val suspendedSession = eveningSession.suspend()

    val beforeIntradayClearing = suspendedSession.resume()

    val intradayClearing = beforeIntradayClearing.startIntradayClearing()

    val afterIntradayClearing = intradayClearing.stopIntradayClearing()

    val clearing = afterIntradayClearing.startClearing()

    val completed = clearing.complete()
  }
}