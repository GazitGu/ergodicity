package com.ergodicity.core.order

import akka.actor.FSM.{Failure, Transition, SubscribeTransitionCallBack}
import akka.actor.{LoggingFSM, Actor, ActorRef}
import com.ergodicity.cgate.DataStream.SubscribeStreamEvents
import com.ergodicity.cgate.Protocol._
import com.ergodicity.cgate.StreamEvent.StreamData
import com.ergodicity.cgate.scheme.OrdBook
import com.ergodicity.cgate.{DataStreamState, Reads}
import com.ergodicity.core.order.OrderBookSnapshot.{OrdersSnapshot, GetOrdersSnapshot}
import com.ergodicity.core.order.SnapshotState.{Loaded, Loading}
import org.joda.time.DateTime

sealed trait SnapshotState

object SnapshotState {

  case object Loading extends SnapshotState

  case object Loaded extends SnapshotState

}

object OrderBookSnapshot {

  case object GetOrdersSnapshot

  case class OrdersSnapshot(revision: Long, moment: DateTime, actions: Seq[Create])
}

class OrderBookSnapshot(OrderBookStream: ActorRef) extends Actor with LoggingFSM[SnapshotState, (Option[Long], Option[DateTime], Seq[Create])] {

  override def preStart() {
    OrderBookStream ! SubscribeStreamEvents(self)
    OrderBookStream ! SubscribeTransitionCallBack(self)
  }

  var pending: Seq[ActorRef] = Seq()

  startWith(Loading, (None, None, Seq()))

  when(Loading) {
    case Event(StreamData(OrdBook.orders.TABLE_INDEX, data), (rev, moment, actions)) =>
      val record = implicitly[Reads[OrdBook.orders]] apply data
      val action = Action(record)
      stay() using(rev, moment, actions :+ action)

    case Event(StreamData(OrdBook.info.TABLE_INDEX, data), (_, _, actions)) =>
      val record = implicitly[Reads[OrdBook.info]] apply data
      val revision = Some(record.get_logRev())
      val moment = Some(new DateTime(record.get_moment()))
      stay() using(revision, moment, actions)

    case Event(Transition(OrderBookStream, DataStreamState.Opened, DataStreamState.Closed), (Some(rev), Some(moment), actions)) =>
      pending foreach (_ ! OrdersSnapshot(rev, moment, actions))
      goto(Loaded)

    case Event(GetOrdersSnapshot, _) =>
      pending = pending :+ sender
      stay()
  }

  when(Loaded) {
    case Event(GetOrdersSnapshot, (Some(rev), Some(moment), actions)) =>
      sender ! OrdersSnapshot(rev, moment, actions)
      stay()

    case Event(GetOrdersSnapshot, state) => stop(Failure("Illegal state = " + state))
  }

  initialize
}