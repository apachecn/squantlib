package net.squantlib.schedule.payoff

import java.util.{Map => JavaMap}

import net.squantlib.util.DisplayUtils._
import net.squantlib.util.JsonUtils._
import net.squantlib.util.{Date, FixingInformation, FormulaParser, JsonUtils}
import net.squantlib.database.DB
import net.squantlib.model.market.Market

import scala.Option.option2Iterable
import net.squantlib.schedule.CalculationPeriod
import net.squantlib.schedule.FixingLeg
import org.codehaus.jackson.JsonNode

import scala.reflect.ClassTag
import scala.collection.JavaConverters._
import org.codehaus.jackson.map.ObjectMapper

trait Payoff extends FixingLeg {

  /*
   * List of reference variables.
   * Price fixings should be provided by map variable -> fixing value.
   */
  override val variables:Set[String]

  val isPriceable:Boolean // checks priceablility of the payoff

  val fixingInfo:FixingInformation

  var isAbsolute:Boolean = false

  val physical:Boolean = false

  override def isSettlementFixed:Boolean = variables.isEmpty || !physical || !settlementFixings.isEmpty

  var nominal:Double = 1.0

  val keywords:Set[String] = jsonMapImpl.keySet

  def jsonMap:Map[String, Any] = inputString.jsonNode match {
    case Some(node) =>
      val fieldnames = node.getFieldNames.asScala.filter(n => !keywords.contains(n)).toSet
      fieldnames.map(n => (n -> node.get(n))).toMap ++ jsonMapImpl
    case _ => jsonMapImpl
  }

  def jsonString:String = {
    val infoMap:JavaMap[String, Any] = jsonMap.asJava
    (new ObjectMapper).writeValueAsString(infoMap)
  }

  def isPaymentFixed:Boolean = isFixed && (!physical || isSettlementFixed)

  def jsonMapImpl:Map[String, Any]

  /*
   * Price assuming all variables fixed at spot market.
   */

  def price(market:Market, pastPayments:List[Double]):Double = {
    priceFlat(market.getFixings(variables), pastPayments)
  }

  final def price(market:Market, period:CalculationPeriod, pastPayments:List[Double]):Double = price(market, eventDates(period).size, pastPayments)

  final def price(market:Market, fixingCount:Int, pastPayments:List[Double]):Double = {
    val mktFixings = market.getFixings(variables)
    price(List.fill(fixingCount)(mktFixings), pastPayments)
  }

  /*
   * Returns price if there's no variable. Returns NaN in case of >0 variables.
   */
  final def price:Double =
    if (isPaymentFixed) priceImpl(List.fill(2)(getFixings), List.empty)
    else if (isPriceable) priceImpl
    else Double.NaN

  def priceImpl:Double

  /*
   * Price in case of multiple event dates and multiple variables.
   * Only refers to the last variable by default but can be overridden.
   */
  final def price(fixings:List[Map[String, Double]], pastPayments:List[Double]):Double =
    if (isPaymentFixed) priceImpl(List.fill(2)(getFixings), pastPayments)
    else if (isPriceable) priceImpl(fixings, pastPayments)
    else Double.NaN

  // PAST PAYMENTS ARE REVERSED (recent first)
  def priceImpl(fixings:List[Map[String, Double]], pastPayments:List[Double]):Double = if (fixings.isEmpty) price else price(fixings, pastPayments)

  def priceFlat(fixings:Map[String, Double], pastPayments:List[Double]):Double = price(List.fill(2)(fixings), pastPayments)

  /*
   * Event dates, usually used in case of >1 fixing dates.
   */
  def eventDates(d:CalculationPeriod):List[Date] = {
    if (physical) List(d.eventDate, d.paymentDate)
    else List(d.eventDate)
  }

  val eventDateFixingIndexFromRight:Int = if (physical) 2 else 1

  def getEventDateFixing[T](fixings:List[T]):Option[T] = fixings.takeRight(eventDateFixingIndexFromRight).headOption

  def triggeredUnderlyings(fixing:Map[String, Double], trigger:Map[String, Double], triggerUp:Boolean):Set[String] = {
    trigger.filter{case (ul, v) =>
      if (triggerUp) fixing.get(ul).collect{case fv => fv >= v}.getOrElse(false)
      else fixing.get(ul).collect{case fv => fv <= v}.getOrElse(false)
    }.keySet
  }

  def isTriggered(fixing:Map[String, Double], trigger:Map[String, Double], triggerUp:Boolean):Boolean =
    !trigger.isEmpty && triggeredUnderlyings(fixing, trigger, triggerUp).size == trigger.size

  //    def triggered(fixing:List[Map[String, Double]], trigger:Option[Map[String, Double]], triggerUp:Boolean) = trigger match {
  //      case None => false
  //      case Some(t) if t.isEmpty => false
  //      case Some(t) if fixing.lastOption.collect{case f => f.isEmpty}.getOrElse(true) => false
  //      case Some(t) => t.forall{case (v, d) =>
  //        if(triggerUp) fixing.last.get(v).collect{case vv => vv >= d}.getOrElse(false)
  //        else fixing.last.get(v).collect{case vv => vv <= d}.getOrElse(false)
  //      }
  //    }

  def terminationAmount(fixing:Map[String, Double], trigNominal:Double, forwardStrikes:Option[Map[String, Double]]):Double = forwardStrikes match {
    case Some(k) => trigNominal * k.map{case (k, v) => fixing(k) / v}.min
    case _ => trigNominal
  }


  /*
   * Returns FixedPayoff if all variables fixing are available on given date.
   * Returns this object if any variables are missing.
   */

  def assignFixings(f:Map[String, Double], pastPayments:List[Double]):Unit = super.assignFixings(f)

  def assignFixings(f:Double, pastPayments:List[Double]):Unit = super.assignFixings(f)

  def assignFixings(eventDate:Date, pastPayments:List[Double]):Unit =
    if (variables.size == 0) {}
    else {
      val fixings:Map[String, Double] = variables.map(v => DB.getPriceOn(v, eventDate).collect{case (_, f) => (v, f)}).flatMap(x => x) (collection.breakOut)
      assignFixings(fixings)
    }

  /*
   * Returns FixedPayoff if all variables fixing are available on given market.
   * Returns this object if any variables are missing.
   */
  def assignFixings(market:Market, pastPayments:List[Double]):Unit = assignFixings(market.getFixings(variables))

  override def assignSettlementFixings(f:Map[String, Double]):Unit = {
    if (physical && (variables subsetOf f.keySet) || f.isEmpty) {
      settlementFixings = f
    }
  }

  def missingInputs:Map[String, Double => Payoff] = Map.empty

  def description:String

  def inputString:String

  def toString:String
}

object Payoff {

  def apply(inputString:String)(implicit fixingInfo:FixingInformation):Option[Payoff] = {
    if (inputString == null || inputString.trim.isEmpty) Some(NullPayoff(""))
    else {
      val po = payoffType(inputString) match {
        case "fixed" => FixedPayoff(inputString)
        case "leps1d" => LEPS1dPayoff(inputString)
        case "linear1d" => Linear1dPayoff(inputString)
        case "putdi" => PutDIPayoff(inputString)
        case "putdiamerican" => PutDIAmericanPayoff(inputString)
        case "callui" => CallUIPayoff(inputString)
        case "forward" => ForwardPayoff(inputString)
        case "rangeforward" => RangeForwardPayoff(inputString)
        case "null" => NullPayoff(inputString)
        case "binary" => BinaryPayoff(inputString)
        case "rangeaccrual" => RangeAccrualPayoff(inputString)
        case "general" => GeneralPayoff(inputString)
        case _ => GeneralPayoff(inputString)
      }

      if (isAbsolute(inputString)) {po.isAbsolute = true}
      customNominal(inputString) match {
        case Some(n) => po.nominal = n
        case _ => {}
      }

      Some(po)
    }
  }

  def payoffType(formula:String):String = formula match {
    case f if f.parseDouble.isDefined => "fixed"
    case f if f.startsWith("leps") => "leps1d"
    case f => formula.parseJsonString("type").orNull
  }

  def isAbsolute(formula:String):Boolean = formula match {
    case f if f.parseDouble.isDefined => false
    case f => formula.parseJsonString("absolute") == Some("1")
    }

  def customNominal(formula:String):Option[Double] = formula match {
    case f if f.parseDouble.isDefined => None
    case f => formula.parseJsonDouble("nominal")
    }

  def simpleCashFlow(amount:Double) = FixedPayoff(amount)(FixingInformation.empty)

  def updateReplacements(inputString:String)(implicit fixingInfo:FixingInformation):String = {
    if (!inputString.trim.startsWith("{")) {return inputString}

    var result = inputString
    inputString.jsonNode match {
      case Some(node) =>
	    val fieldnames = node.getFieldNames.asScala.filter(n => n.startsWith("^")).toSet
        fieldnames.foreach(n => {
          val currentvalue = node.get(n).getTextValue()
          val updatedvalue = fixingInfo.update(currentvalue)
          result = result.replace(n, FormulaParser.calculate(updatedvalue).getOrElse(Double.NaN).toString)
        })
	    case None => {}
	  }
    result
  }

  def nodeToComputedMap(node:JsonNode, s:String, variable:List[String])(implicit fixingInfo:FixingInformation):Map[String, Double] = {
    node.getOption(s) match {
      case Some(n) =>
        JsonUtils.nodeToHashMap(n, variable)
          .filter{case (k, v) => v != null && v != "null"}
          .map{case (k, v) => (k, fixingInfo.updateCompute(v))}
          .collect { case (k, v) => (k, v.getOrElse(Double.NaN)) }
          .toMap
      case _ => Map.empty
    }
  }

}
