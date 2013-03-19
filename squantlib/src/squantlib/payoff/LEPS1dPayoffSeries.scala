package squantlib.payoff

import scala.collection.JavaConversions._
import org.codehaus.jackson.JsonNode
import org.codehaus.jackson.map.ObjectMapper

import squantlib.util.DisplayUtils._
import squantlib.util.JsonUtils._

/**
 * Interprets JSON formula for series of LEPS1dPayoffs.
 * JSON format:
 * - {type:"leps1dseries", variable:string, legs:Array[formula]}, where
 *   formula = {description:String, payoff:Array[{minrange:double, maxrange:double, mult:double, add:double, description:string}]}
 *   payment for array(i) is 
 *     if minrange(i) <= X < maxrange(i) => mult(i) * variable + add(i)
 *     otherwise zero
 */

object LEPS1dPayoffSeries {
  
	def apply(formula:String):List[LEPS1dPayoff] = {
	  val variable:String = formula.parseJsonString("variable").orNull
	  
	  formula.jsonNode("legs") match {
	    
	    case None => List.empty
	    
	    case Some(node) => node.parseList.map(n => LEPS1dPayoff(variable, n.get("payoff"), n.parseString("description").orNull))
	    
//	    case Some(node) if node isArray => node.getElements.map { n => {
//	      val description:String = n.parseString("description").orNull
//	      LEPS1dPayoff(variable, n.get("payoff"), description)
//	    }}.toList
//	    
//	    case Some(node) if node isObject => {
//	      val description:String = node.parseString("description").orNull
//	      List(LEPS1dPayoff(variable, node.get("payoff"), description))
//	    }
//	    
//	    case _ => List.empty
	  }
	}
	  
}