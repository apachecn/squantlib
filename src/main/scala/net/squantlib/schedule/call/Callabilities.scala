package net.squantlib.schedule.call

import scala.language.postfixOps
import scala.collection.LinearSeq
import scala.collection.JavaConverters._
import net.squantlib.util.DisplayUtils._
import net.squantlib.util.JsonUtils._
import net.squantlib.schedule.FixingLegs
import net.squantlib.util.{FixingInformation, UnderlyingFixing, Date}
import net.squantlib.schedule.{Schedule, KnockInCondition}
import net.squantlib.schedule.payoff.Payoffs
import com.fasterxml.jackson.databind.JsonNode

case class Callabilities(calls:List[Callability]) extends LinearSeq[Callability] with FixingLegs[Callability]{
  
	val underlyings:Set[String] = calls.map(_.underlyings).flatten.toSet
	
	def bermudans:List[Boolean] = calls.map(_.isBermuda)

	def triggers:List[Option[UnderlyingFixing]] = calls.map(c =>
	  if (c.isTriggered) Some(UnderlyingFixing(c.triggers.keySet.map(ul => (ul, BigDecimal(0.0))).toMap))
	  else if (c.isFixed) None
	  else if (c isTrigger) Some(c.triggers)
	  else None
  )

  def forwardStrikes:List[Option[UnderlyingFixing]] = calls.map(c =>
    if (c.forward.isEmpty) None
    else Some(c.forward)
  )
  
  def triggerUps:List[Boolean] = calls.map(c => c.triggerCondition.triggerUp).toList
	  
	def triggerValues(variables:List[String]):List[List[Option[BigDecimal]]] = calls.map(_.triggerValues(variables))
	
	def isTargetRedemption = calls.exists(_.isTargetRedemption)

	def targetRedemptions:List[Option[BigDecimal]] = calls.map(_.targetRedemptionCondition.target)
	
	def isTriggeredByTrigger:Boolean = calls.exists(c => c.isFixed && c.fixedTriggerByTrigger == Some(true))

  def isTriggeredByTarget:Boolean = calls.exists(c => c.fixedTriggerByTargetRedemption == Some(true))

	def isTriggered:Boolean = calls.exists(c => c.isFixed && c.fixedTrigger == Some(true))
  
	val isPriceable:Boolean = calls.forall(_.isPriceable)
	
	val bonusAmount:List[BigDecimal] = calls.map(_.bonusAmount)
	
	def fill(legs:Int) = size match {
	  case l if l > legs => Callabilities(this.takeRight(legs))
	  case l if l == legs => Callabilities(this)
	  case l if l < legs => Callabilities(List.fill(legs - l)(Callability.empty) ++ this)
	}
	
	def ++(another:Callabilities) = new Callabilities(calls ++ another.calls)
	
	def :+(call:Callability) = new Callabilities(calls :+ call)
	
	override def toString = calls.map(_.toString).mkString("\n")
	
  def apply(i:Int):Callability = calls(i)

  def isBermuda:Boolean = calls.exists(_.isBermuda)

  val isTrigger = calls.exists(_.isTrigger) && !isTriggeredByTrigger
    
	def triggerCheck(fixings:List[UnderlyingFixing]):List[Boolean] = (fixings, calls).zipped.map{case (f, c) => c.isTriggered(f)}
	
	override def isEmpty:Boolean = calls.isEmpty || calls.forall(_.isEmpty)
	
	override def head:Callability = calls.head
	
	override def tail = calls.tail
	
	override def length = calls.length
	
	override def iterator:Iterator[Callability] = calls.iterator
	
	override def toList:List[Callability] = calls
	
	def reorder(order:List[Int]) = new Callabilities((0 to calls.size-1).map(i => calls(order(i))) (collection.breakOut)) 
	
	override def isFixed:Boolean = fixinglegs.forall(_.isFixed) || isTriggered
	
	override val fixinglegs = calls
	
	def assignAccumulatedPayments(schedule:Schedule, payoffs:Payoffs) = {
    val payments = (schedule, payoffs).zipped.map{case (s, p) => (s.paymentDate, if (p.price.isNaN) 0.0 else p.price * s.dayCount)}
    (schedule, calls).zipped.foreach{case (s, c) => 
      c.accumulatedPayments = Some(payments.filter{case (d, p) => d <= s.paymentDate}.map{case (d, p) => p}.sum)
    }
	}
	
}


object Callabilities {
	
	def empty:Callabilities = new Callabilities(List.empty)
	
	def apply(calls:LinearSeq[Callability]):Callabilities = new Callabilities(calls.toList)
	
	def bermudan(formula:String, legs:Int):List[Boolean] = bermudanList(formula, legs) match {
	  case berms if !berms.isEmpty && berms.takeRight(1).head => berms.dropRight(1) :+ false
	  case berms => berms
	}
	
  def bermudanList(formula:String, nbLegs:Int):List[Boolean] = formula.jsonNode match {
    case Some(b) if b isArray =>
      val bermList = b.asScala.map(_ match {
        case s if s.isTextual => s.parseString == Some("berm")
        case s if s.isObject => s.parseString("type") == Some("berm")
        case _ => false
      }).toList
      
      List.fill(nbLegs - 2 - b.size)(false) ++ bermList ++ List(false, false)

    case _ => List.fill(nbLegs)(false)
  }
  
  def targetList(formula:String, nbLegs:Int):List[Option[Double]] = formula.jsonNode match {
    case Some(bs) if bs isArray =>
      val targetList = bs.asScala.map(_.get("target").parseDouble).toList
      List.fill(nbLegs - 2 - bs.size)(None) ++ targetList ++ List(None, None)

    case _ => List.fill(nbLegs)(None)
  }

  def callOptionList(
    formula:String,
    nbLegs:Int
  )(implicit fixingInfo:FixingInformation):List[CallOption] = formula.jsonNode match {
    case Some(bb) if bb isArray => 
      val optlist = bb.asScala.map{case b =>
        val invertedStrike:Boolean = b.parseInt("inverted_strike").getOrElse(0) == 1
        val invertedTrigger:Boolean = (invertedStrike || b.parseInt("inverted_trigger").getOrElse(0) == 1)
        val invertedForward:Boolean = (invertedStrike || b.parseInt("inverted_forward").getOrElse(0) == 1)

        val triggerUp = b.parseString("trigger_type").getOrElse("up") == "up"
        val forwardMap = b.getOption("forward").collect{case k => k.parseStringFields}.getOrElse(Map.empty)
        val forward = assignFixings(forwardMap)
        val bonus = b.parseDouble("bonus").getOrElse(0.0)
        val removeSatisfiedTriggers = b.parseInt("memory").getOrElse(0) == 1
        val forwardStrikes = {
          if (invertedForward && forward.keys.forall(_.size == 6)) forward.map{case (k, v) => ((k takeRight 3) + (k take 3), if(v != 0.0) 1.0 / v else 0.0)}.toMap
          else forward
        }

        CallOption(
          triggerUp = if (invertedTrigger) !triggerUp else triggerUp,
          forward = UnderlyingFixing(forwardStrikes),
          forwardInputString = forwardMap,
          bonus = bonus,
          invertedTrigger = invertedTrigger,
          invertedForward = invertedForward,
          removeSatisfiedTriggers = removeSatisfiedTriggers
        )

      }.toList
        
      List.fill(nbLegs - 2 - bb.size)(CallOption.empty) ++ optlist ++ List(CallOption.empty, CallOption.empty)
    
    case _ => List.fill(nbLegs)(CallOption.empty)
  }

  private def underlyingStrikeList(
    formula:String,
    nbLegs:Int,
    underlyings:List[String],
    keyword: String
  ):List[Map[String, String]] = {

    formula.jsonNode match {
      case Some(bs) if bs isArray =>
        val trigList:List[Map[String, String]] = bs.asScala.map(_ match {
          case n if (n isArray) =>
            (underlyings, n.asScala.map(_.parseString.getOrElse("")).toList).zipped.toMap

          case n if (n.isObject) =>
            n.getOption(keyword) match {
              case Some(trigs) if trigs.isArray =>
                (underlyings, trigs.asScala.map(_.parseString.getOrElse(""))).zipped.toMap
              case Some(trigs) if trigs.isObject =>
                trigs.parseStringFields
              case _ => Map.empty[String, String]
            }

          case _ =>
            Map.empty[String, String]

        }).toList

        List.fill(nbLegs - bs.size - 2)(Map.empty[String, String]) ++ trigList ++ List.fill(2)(Map.empty[String, String])

      case _ => List.fill(nbLegs)(Map.empty)
    }
  }


  private def triggerToAssignedTrigger(
    trigs:List[Map[String, String]],
    invertedStrikes:List[Boolean]
  )(implicit fixingInfo:FixingInformation):List[Map[String, Double]] =
    (trigs, invertedStrikes).zipped.map{
      case (trig, inverted) if inverted =>
        val assignedTrig:Map[String, Double] = assignFixings(trig)
        if (inverted && trig.keys.forall(_.size == 6)) {
          assignedTrig.map{
            case (k, v) =>
              val ul = (k takeRight 3) + (k take 3)
              val p = if(v != 0.0) 1.0 / v else 0.0
              (ul, p.getRoundedDouble(ul))
          }
        } else assignedTrig

      case (trig, inverted) => assignFixings(trig)
    }
  
  private def assignFixings(stks:Map[String, String])(implicit fixingInfo:FixingInformation):Map[String, Double] = {
    stks.map{case (k, v) =>
      (k, fixingInfo.updateCompute(v))
    }.collect{case (k, v) =>
      (k, v.collect{case vv => vv.getRoundedDouble(k)}.getOrElse(Double.NaN))
    }
  }
  
  def triggerMap(
    underlyings:List[String],
    triggers:List[List[Option[Double]]]
  )(implicit fixingInfo:FixingInformation):List[UnderlyingFixing] = {
    triggers.map(trigs => {
      UnderlyingFixing((underlyings, trigs).zipped.collect{case (k, Some(v)) => (k, v)}.toMap)
    })
  }

  private def mapList(formula:String, legs:Int):List[Map[String, Any]] = formula.jsonNode match {
    case Some(bs) if bs.isArray =>
      val formulaList:List[Map[String, Any]] = bs.asScala.map(_ match {
        case n if (n.isObject) => n.parseMap
        case _ => Map.empty[String, Any]
      }).toList

      List.fill(legs - bs.size - 2)(Map.empty[String, String]) ++ formulaList ++ List.fill(2)(Map.empty[String, Any])

    case _ => List.fill(legs)(Map.empty)
  }

  def apply(
	  formula:String, 
    underlyings:List[String], 
    legs:Int
  )(implicit fixingInfo:FixingInformation):Callabilities = {
    
    val bermudans = bermudan(formula, legs)

    val trigFormulas:List[Map[String, String]] = underlyingStrikeList(formula, legs, underlyings, "trigger")

    val callOptions:List[CallOption] = callOptionList(formula, legs)

    val invertedTriggerList:List[Boolean] = callOptions.map(c => c.invertedTrigger)

    val trigMap:List[UnderlyingFixing] = triggerToAssignedTrigger(trigFormulas, invertedTriggerList).map(vs => UnderlyingFixing(vs))

    val targets:List[Option[BigDecimal]] = targetList(formula, legs).map(vs => vs.flatMap{case v => v.getRoundedDecimal})

    val baseFormulas:List[Map[String, Any]] = mapList(formula, legs)

    val resetNewTriggerFormulas:List[Map[String, String]] = underlyingStrikeList(formula, legs, underlyings, "reset_new_trigger")
    val resetNewTriggerMap:List[UnderlyingFixing] = triggerToAssignedTrigger(resetNewTriggerFormulas, invertedTriggerList).map(vs => UnderlyingFixing(vs))

    val resetKnockInConditions:List[KnockInCondition] = {
      val resetStrikeFormulas:List[Map[String, String]] = underlyingStrikeList(formula, legs, underlyings, "reset_strike")
      val resetStrikeMap:List[UnderlyingFixing] = triggerToAssignedTrigger(resetStrikeFormulas, invertedTriggerList).map(vs => UnderlyingFixing(vs))

      formula.jsonNode match {
        case Some(bs) if bs.isArray =>
          val allLegs = List.fill(legs - 2 - bs.size)(None) ++ bs.asScala.map(s => Some(s)) ++ List(None, None)

          (allLegs, resetStrikeMap).zipped.map{
            case (Some(aleg), strike) if !strike.isEmpty =>
              val knockInOnEqual:Boolean = aleg.get("reset_on_equal").parseInt.collect{case i => i == 1}.getOrElse(true)
              val knockInOnClose:Boolean = aleg.get("reset_reftype").parseString.collect{case i => i == "closing"}.getOrElse(true)

              (aleg.get("reset_refstart").parseDate, aleg.get("reset_refend").parseDate) match {
                case (Some(dStart), Some(dEnd)) =>
                  KnockInCondition(
                    trigger = strike,
                    refStart = dStart,
                    refEnd = dEnd,
                    finalTrigger = UnderlyingFixing.empty,
                    closeOnly = knockInOnClose,
                    triggerDown = aleg.get("reset_down").parseInt.collect{case i => i != 0}.getOrElse(true),
                    triggerOnEqual = knockInOnEqual
                  )
                case _ => KnockInCondition.empty
              }


            case _ => KnockInCondition.empty
          }

        case _ => List.fill(legs)(KnockInCondition.empty)
      }
    }

    val calls = (bermudans.zip(trigFormulas)).zip(trigMap.zip(targets)).zip(callOptions.zip(baseFormulas)).zip(resetKnockInConditions.zip(resetNewTriggerMap)).map{
      case ((((berm, f), (trig, tgt)), (callOption, baseFormula)), (resetKnockInCondition, resetStrikes)) =>

        var inputString:Map[String, Any] = baseFormula

        inputString = inputString.updated("type", (
          if (berm) "berm"
          else if (tgt.isDefined) "target"
          else if (!f.isEmpty) "trigger"
          else baseFormula.get("type") match {
            case Some(v) => v
            case _ => "unknown"
          }
        ))

        if (tgt.isDefined) {
          inputString = inputString.updated("target", tgt.getOrElse(Double.NaN))
        } else if (!f.isEmpty) {
          inputString = inputString.updated("trigger", f).updated("trigger_type", (if(callOption.triggerUp) "up" else "down"))
        }
  
        if (!callOption.forward.isEmpty) {
          inputString = inputString.updated("forward", callOption.forwardInputString)
        }
        
        if (callOption.invertedTrigger) {
          inputString = inputString.updated("inverted_trigger", 1)
        }

        if (callOption.invertedForward) {
          inputString = inputString.updated("inverted_forward", 1)
        }

        inputString = inputString.updated("bonus", callOption.bonus)
          
        Callability.apply(
          bermudan = berm,
          triggers = trig,
          targetRedemption = tgt,
          callOption = callOption,
          resetCondition = resetKnockInCondition,
          resetStrikes = resetStrikes,
          inputString = inputString,
          accumulatedPayments = None,
          simulatedFrontier= UnderlyingFixing.empty
        )
    }
    
    Callabilities(calls)
  }

//
//	def apply(
//    bermudans:List[Boolean],
//    triggers:List[List[Option[Double]]],
//    targets:List[Option[Double]],
//    resetKnockInConditions: List[KnockInCondition],
//    resetKnockInNewTriggers: List[UnderlyingFixing],
//    underlyings:List[String],
//    callOptions:List[CallOption]
//  )(implicit fixingInfo:FixingInformation):Callabilities = {
//
//    Callabilities(
//      bermudans.zip(triggerMap(underlyings, triggers)).zip(callOptions.zip(targets)).zip(resetKnockInConditions.zip(resetKnockInNewTriggers)).map{
//        case (((berm, trig), (callOption, tgt)), (resetKnockInCondition, resetStrikes)) =>
//          Callability(
//            bermudan = berm,
//            triggers = trig,
//            targetRedemption = tgt.flatMap{case v => v.getRoundedDecimal},
//            callOption = callOption,
//            resetCondition = resetKnockInCondition,
//            resetStrikes = resetStrikes,
//            inputString = Map.empty[String, Any],
//            accumulatedPayments = None,
//            simulatedFrontier= UnderlyingFixing.empty
//          )
//      }
//    )
//	}
	  

}

