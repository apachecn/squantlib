package squantlib.model.bond

import squantlib.util.Date
import org.jquantlib.daycounters.{Actual365Fixed, DayCounter}
import squantlib.util.SimpleCache
import squantlib.pricing.model.PricingModel
import squantlib.schedule.call.Callabilities
import squantlib.schedule.{ScheduledPayoffs, CalculationPeriod}
import squantlib.model.Market

/**
 * Bond class with enclosed risk analysis functions.
 */ 

trait ExtendedSchedule {
  
  self : BondModel =>
  
  def market:Option[Market]
  
  def valueDate:Option[Date] = market.collect{case mkt => mkt.valuedate}
  
  val cache:SimpleCache = null
  
  def isIssued:Option[Boolean] = valueDate.collect{case d => isIssuedOn(d)}
  
  def isScheduleMatured:Option[Boolean] = valueDate.collect { case vd => isScheduleMaturedOn(vd)}

  def isEarlyTerminated:Option[Boolean] = valueDate.collect{case d => isEarlyTerminatedOn(d)}
  
  def isTerminated:Option[Boolean] = valueDate.collect{case d => isScheduleMaturedOn(d) || isEarlyTerminatedOn(d)}
  
  /*
   * Remaining life in number of years
   */
  def remainingLife:Option[Double] = valueDate.collect{ case d => Date.daycount(d, terminationDate, new Actual365Fixed).max(0.0)}
  
  /*  
   * Returns "live" payment schedules
   *   @returns element 1: Schedule containing legs with payment date after market value date or specified value date.
   *       element 2: Payoffs containing legs with payment dates after market value date or specified value date.
   */
  def livePayoffs:ScheduledPayoffs = valueDate.collect {case d => livePayoffs(d)}.getOrElse(ScheduledPayoffs.empty)

  def livePayoffs(vd:Date):ScheduledPayoffs = {
    val p = (earlyTerminationDate,earlyTerminationAmount) match {
      case (Some(d), _) if vd ge d => ScheduledPayoffs.empty
      case (Some(d), Some(a)) => scheduledPayoffs.after(vd).called(d, a, db.calendar, db.paymentAdjust).withValueDate(vd)
      case _ => scheduledPayoffs.after(vd).withValueDate(vd)
    }
    p
  }
  
  def liveCoupons:ScheduledPayoffs = livePayoffs.filtered{case (period, _, _) => !period.isAbsolute}
  
  def liveCoupons(vd:Date):ScheduledPayoffs = livePayoffs(vd).filtered{case (period, _, _) => !period.isAbsolute}
  
  /*  
   * Returns "live" triggers
   *   @returns list of remaining triggers
   */
  
  def liveCallabilities:Callabilities = livePayoffs.calls
  
  def liveTriggers:List[List[Option[Double]]] = livePayoffs.calls.triggerValues(underlyings)
  
  def liveTriggers(vd:Date):List[List[Option[Double]]] = livePayoffs(vd).calls.triggerValues(underlyings)
  
  /*  
   * Returns "live" bermudan call options
   *   @returns list of remaining bermudan calls
   */
  def liveBermudans:List[(CalculationPeriod, Boolean)] = livePayoffs.map{case (d, _, c) => (d, c.isBermuda)} (collection.breakOut)
    
  def liveBermudans(vd:Date):List[(CalculationPeriod, Boolean)] = livePayoffs(vd).map{case (d, _, c) => (d, c.isBermuda)}(collection.breakOut)

  /*  
   * Returns coupons fixed with current spot market (not forward!). 
   */
  def spotFixedRates:List[(CalculationPeriod, Double)] = {
    def retrieverfunc:List[(CalculationPeriod, Double)] = 
      livePayoffs.map{case (d, p, _) => 
        (d, market match { 
          case Some(mkt) => p.price(mkt) 
          case None => Double.NaN})}(collection.breakOut)
          
    if (cache == null) retrieverfunc
    else cache.getOrUpdate("SPOTFIXEDRATES",retrieverfunc)
  }
    
  def spotFixedRates(vd:Date):List[(CalculationPeriod, Double)] = spotFixedRates.filter{case (p, d) => (p.paymentDate gt vd)}
    
  def spotFixedAmount:List[(Date, Double)] = spotFixedRates.map{case (period, rate) => (period.paymentDate, rate * period.dayCount)}
  
  def spotFixedAmount(vd:Date):List[(Date, Double)] = spotFixedAmount.filter{case (d, _) => (d gt vd)}
    
  def spotFixedRatesAll:List[(CalculationPeriod, Double)] = cache.getOrUpdate("SPOTFIXEDRATESALL",
      scheduledPayoffs.map{case (d, p, _) => (d, market match { case Some(mkt) => p.price(mkt) case None => Double.NaN})} (collection.breakOut)
    )
    
  def spotFixedAmountAll:List[(Date, Double)] = spotFixedRatesAll.map{case (period, rate) => (period.paymentDate, rate * period.dayCount)}
  
  def spotCashflowDayfrac(dc:DayCounter):List[(Double, Double)] = spotFixedAmount.map{
    case (payday, amount) => (Date.daycount(valueDate.get, payday, dc), amount)}
  
  
}
