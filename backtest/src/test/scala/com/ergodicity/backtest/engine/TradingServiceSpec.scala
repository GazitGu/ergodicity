package com.ergodicity.backtest.engine

import akka.actor.ActorSystem
import akka.event.Logging
import akka.testkit
import akka.testkit._
import akka.util.Timeout
import akka.util.duration._
import akka.pattern.ask
import com.ergodicity.backtest.Mocking
import com.ergodicity.backtest.cgate._
import com.ergodicity.backtest.service.{RepliesService, OrdersService, SessionsService}
import com.ergodicity.core.Market.{Futures, Options}
import com.ergodicity.core.OrderType.ImmediateOrCancel
import com.ergodicity.core._
import com.ergodicity.core.broker.Action.AddOrder
import broker._
import com.ergodicity.core.session.InstrumentState
import com.ergodicity.engine.Listener._
import com.ergodicity.engine.Services.StartServices
import com.ergodicity.engine.service.Trading.OrderExecution
import com.ergodicity.engine.service.{Trading, InstrumentData, ReplicationConnection}
import com.ergodicity.engine.underlying.{UnderlyingPublisher, UnderlyingConnection}
import com.ergodicity.engine.{ServicesActor, Engine, ServicesState}
import com.ergodicity.schema.{OptSessContents, FutSessContents, Session}
import order.OrderActor
import org.joda.time.DateTime
import org.mockito.Mockito
import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}
import akka.dispatch.Await
import scala.Left
import akka.actor.FSM.Transition
import scala.Some
import com.ergodicity.backtest.service.SessionContext
import com.ergodicity.core.OptionContract
import com.ergodicity.core.FutureContract
import akka.actor.FSM.CurrentState
import com.ergodicity.engine.service.Trading.Buy
import com.ergodicity.core.SessionId
import scala.Right
import com.ergodicity.backtest.cgate.PublisherStubActor.PublisherContext
import akka.actor.FSM.SubscribeTransitionCallBack

class TradingServiceSpec extends TestKit(ActorSystem("TradingServiceSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
  val log = Logging(system, self)

  val SystemTrade = false

  override def afterAll() {
    system.shutdown()
  }

  class TestEngine(implicit system: ActorSystem) extends Engine with UnderlyingConnection with UnderlyingPublisher with FutInfoListener with OptInfoListener with FutOrdersListener with OptOrdersListener with RepliesListener {
    val ServicesActor = system.deadLetters
    val StrategiesActor = system.deadLetters

    // Connection Stub
    val connectionStub = TestFSMRef(new ConnectionStubActor, "ConnectionStub")

    // Underlying Connection
    lazy val underlyingConnection = ConnectionStub wrap connectionStub

    // Underlying publisher
    val publisherName = "TestPublisher"
    val brokerCode = "000"
    lazy val underlyingPublisher = PublisherStub wrap publisherStub

    // Replication streams stubs
    val futInfoListenerStub = TestFSMRef(new ReplicationStreamListenerStubActor, "FutInfoListenerStub")
    val optInfoListenerStub = TestFSMRef(new ReplicationStreamListenerStubActor, "OptInfoListenerStub")
    val futOrdersListenerStub = TestFSMRef(new ReplicationStreamListenerStubActor, "FutOrdersStub")
    val optOrdersListenerStub = TestFSMRef(new ReplicationStreamListenerStubActor, "OptOrdersStub")

    // Publisher and replies stream stubs
    val repliesListenerStub = TestFSMRef(new ReplyStreamListenerStubActor, "RepliesListenerStub")
    val publisherStub = TestFSMRef(new PublisherStubActor, "PublisherStub")

    // Listeners
    lazy val repliesListener = ListenerBindingStub wrap repliesListenerStub

    lazy val futInfoListener = ListenerBindingStub wrap futInfoListenerStub
    lazy val optInfoListener = ListenerBindingStub wrap optInfoListenerStub
    lazy val futOrdersListener = ListenerBindingStub wrap futOrdersListenerStub
    lazy val optOrdersListener = ListenerBindingStub wrap optOrdersListenerStub
  }

  class TestServices(val engine: TestEngine) extends ServicesActor with ReplicationConnection with InstrumentData with Trading

  val sessionId = SessionId(100, 100)

  val begin = new DateTime(2012, 1, 1, 10, 0)
  val end = begin.withHourOfDay(20)

  val futureContract = FutureContract(IsinId(100), Isin("FISIN"), ShortIsin("FISINS"), "Future")
  val optionContract = OptionContract(IsinId(101), Isin("OISIN"), ShortIsin("OISINS"), "Option")

  val session = Session(Mocking.mockSession(sessionId.fut, sessionId.opt, begin, end))
  val futures = FutSessContents(Mocking.mockFuture(sessionId.fut, futureContract.id.id, futureContract.isin.isin, futureContract.shortIsin.shortIsin, futureContract.name, 115, InstrumentState.Assigned.toInt)) :: Nil
  val options = OptSessContents(Mocking.mockOption(sessionId.fut, optionContract.id.id, optionContract.isin.isin, optionContract.shortIsin.shortIsin, optionContract.name, 115)) :: Nil

  implicit val sessionContext = SessionContext(session, futures, options)

  implicit val timeout = Timeout(1.second)

  "Trading Service" must {
    "forward commands to publisher strategy" in {
      val engine = testkit.TestActorRef(new TestEngine, "Engine")
      val services = TestActorRef(new TestServices(engine.underlyingActor), "Services")

      services ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(services, ServicesState.Idle))

      implicit val sessions = new SessionsService(engine.underlyingActor.futInfoListenerStub, engine.underlyingActor.optInfoListenerStub)
      val assigned = sessions.assign(session, futures, options)
      assigned.start()

      services ! StartServices
      expectMsg(3.seconds, Transition(services, ServicesState.Idle, ServicesState.Starting))
      expectMsg(10.seconds, Transition(services, ServicesState.Starting, ServicesState.Active))

      given("engine trading service")
      val trading = services.underlyingActor.service(Trading.Trading)

      val publisherStrategy = Mockito.mock(classOf[PublisherStrategy])
      val futuresReplies = Mockito.mock(classOf[RepliesService[Futures]])
      val optionsReplies = Mockito.mock(classOf[RepliesService[Options]])

      given("mocked publisher strategy")
      Mockito.when(publisherStrategy.apply(AddOrder(futureContract.isin, 1, 100, ImmediateOrCancel, OrderDirection.Buy))).thenReturn(Right(OrderId(1)))
      Mockito.when(publisherStrategy.apply(AddOrder(optionContract.isin, 1, 100, ImmediateOrCancel, OrderDirection.Buy))).thenReturn(Right(OrderId(2)))
      Mockito.when(publisherStrategy.apply(AddOrder(futureContract.isin, 1, 101, ImmediateOrCancel, OrderDirection.Buy))).thenReturn(Left(BrokerTimedOutException))
      Mockito.when(publisherStrategy.apply(AddOrder(optionContract.isin, 1, 101, ImmediateOrCancel, OrderDirection.Buy))).thenReturn(Left(BrokerTimedOutException))

      and("publisher context")
      engine.underlyingActor.publisherStub ! PublisherContext(publisherStrategy, futuresReplies, optionsReplies)

      when("buy future contract")
      trading ! Buy(futureContract, 1, 100)
      Thread.sleep(100)

      then("should process broker Action with underlying strategy")
      Mockito.verify(publisherStrategy).apply(AddOrder(futureContract.isin, 1, 100, ImmediateOrCancel, OrderDirection.Buy))
      Mockito.verify(futuresReplies).reply(1, OrderId(1))

      when("buy option contract")
      trading ! Buy(optionContract, 1, 100)
      Thread.sleep(100)

      then("should process broker Action with underlying strategy")
      Mockito.verify(publisherStrategy).apply(AddOrder(optionContract.isin, 1, 100, ImmediateOrCancel, OrderDirection.Buy))
      Mockito.verify(optionsReplies).reply(2, OrderId(2))

      when("fail buy future contract")
      trading ! Buy(futureContract, 1, 101)
      Thread.sleep(100)

      then("should process failure with underlying strategy")
      Mockito.verify(publisherStrategy).apply(AddOrder(futureContract.isin, 1, 101, ImmediateOrCancel, OrderDirection.Buy))
      Mockito.verify(futuresReplies).fail[OrderId](3, BrokerTimedOutException)

      when("fail buy option contract")
      trading ! Buy(optionContract, 1, 101)
      Thread.sleep(100)

      then("should process failure with underlying strategy")
      Mockito.verify(publisherStrategy).apply(AddOrder(optionContract.isin, 1, 101, ImmediateOrCancel, OrderDirection.Buy))
      Mockito.verify(optionsReplies).fail[OrderId](4, BrokerTimedOutException)
    }

    "support ExecuteOnDeclaredPrice strategy" in {
      implicit val timeout = Timeout(1.second)

      val engine = testkit.TestActorRef(new TestEngine, "Engine")
      val services = TestActorRef(new TestServices(engine.underlyingActor), "Services")

      services ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(services, ServicesState.Idle))

      implicit val sessions = new SessionsService(engine.underlyingActor.futInfoListenerStub, engine.underlyingActor.optInfoListenerStub)
      val assigned = sessions.assign(session, futures, options)
      assigned.start()

      services ! StartServices
      expectMsg(3.seconds, Transition(services, ServicesState.Idle, ServicesState.Starting))
      expectMsg(10.seconds, Transition(services, ServicesState.Starting, ServicesState.Active))

      given("engine trading service")
      val trading = services.underlyingActor.service(Trading.Trading)

      val orders = new OrdersService(engine.underlyingActor.futOrdersListenerStub, engine.underlyingActor.optOrdersListenerStub)
      val publisherStrategy = new PublisherStrategy.ExecuteOnDeclaredPrice(orders)
      val futuresReplies = new RepliesService[Futures](engine.underlyingActor.repliesListenerStub)
      val optionsReplies = new RepliesService[Options](engine.underlyingActor.repliesListenerStub)

      and("publisher context")
      engine.underlyingActor.publisherStub ! PublisherContext(publisherStrategy, futuresReplies, optionsReplies)

      when("buy future contract")
      val execution = Await.result((trading ? Buy(futureContract, 1, 100)).mapTo[OrderExecution], 1.second)

      then("get successfull order execution")
      assert(execution.security == futureContract)
      execution.subscribeOrderEvents(self)
      expectMsg(OrderActor.OrderEvent(execution.order, order.Create(execution.order)))
      expectMsg(OrderActor.OrderEvent(execution.order, order.Fill(1, 0, Some(1, 100))))

      when("try to cancel order")
      then("should fail with exception")
      intercept[ActionFailedException] {
        Await.result(execution.cancel, 1.second)
      }

      when("try to buy not assigned contract")
      then("should fail with exception")
      intercept[ActionFailedException] {
        val badContract = FutureContract(IsinId(111), Isin("BadIsin"), ShortIsin("Bad"), "SomeNotAssignedFuture")
        Await.result((trading ? Buy(badContract, 1, 100)).mapTo[OrderExecution], 1.second)
      }
    }
  }
}