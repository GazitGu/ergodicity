package com.ergodicity.core.common

case class Isin(id: Int, code: String, shortCode: String)

sealed trait Security {
  def isin: Isin
}

case class BasicSecurity(isin: Isin) extends Security

sealed trait Derivative extends Security

case class FutureContract(isin: Isin, name: String) extends Derivative

case class OptionContract(isin: Isin, name: String) extends Derivative