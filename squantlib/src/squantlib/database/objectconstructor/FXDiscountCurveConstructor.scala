package squantlib.database.objectconstructor

import scala.collection.immutable.TreeMap
import squantlib.database.schemadefinitions.InputParameter
import squantlib.model.discountcurve.FXDiscountCurve
import squantlib.model.currencies.RateConvention
import org.jquantlib.time.{Period => JPeriod, Date => JDate}
import org.jquantlib.currencies.Currency


object FXDiscountCurveConstructor {
  import squantlib.parameter.yieldparameter.FlatVector
  
	val swappointKey = "SwapPt"
	val fxKey = "FX"
	val pivotccy = "USD"
	  
	/**
	 * Constructs LiborDiscountCurve from InputParameter per each combination of currency & paramset.
	 * Invalid input parameter sets are ignored.
	 * @param set of InputParameter
	 * @returns map from (Currency, ParamSet) to LiborDiscountCurve
	 */
  	def getcurves(params:Set[InputParameter]):Map[(String, String), FXDiscountCurve] = {
	  val conventions:Map[String, RateConvention] = RateConvention.getConvention.filter{case (k, v) => v.useFXdiscount}
  	  val dateassetgroup = params.groupBy(p => (p.asset, p.paramset)).filter{case((k1, k2), v) => conventions.keySet.contains(k1)}
  	  val instrumentgroup = dateassetgroup.map{ case (k, v) => (k, v.groupBy(p => p.instrument))} 
  	  val nonemptyinstruments = instrumentgroup.filter{ case (k, v) => (v.keySet.contains(swappointKey) && v.keySet.contains(fxKey))}
  	  
  	  val parresult = nonemptyinstruments.par.map{ case ((k1, k2), v) => {
  		  val conv = conventions(k1)
  		  val valuedate = new JDate(v(swappointKey).head.paramdate)
  		  def toTreeMap(k:String) = TreeMap(v(k).toSeq.map(p => (new JPeriod(p.maturity), p.value)) :_*)
  		  val swapptcurve = conv.swappoint_constructor(valuedate, toTreeMap(swappointKey))
  		  
  		  ((k1, k2), new FXDiscountCurve(swapptcurve, v(fxKey).first.value))
  	  	}
  	  }
  	  parresult.seq
  	}
  	  
	/**
	 * Constructs LiborDiscountCurve from InputParameter per each currency, only with specified paramset.
	 * Invalid input paramter sets or unmatched paramset ID are ignored.
	 * @param set of InputParameter
	 * @returns map from Currency to LiborDiscountCurve
	 */
  	def getcurves(params:Set[InputParameter], paramset:String):Map[String, FXDiscountCurve] = {
      val validparams = params.filter(p => p.paramset == paramset)
  	  getcurves(validparams) map { case ((k1, k2), v) => (k1, v) }
  	}
  	
} 
