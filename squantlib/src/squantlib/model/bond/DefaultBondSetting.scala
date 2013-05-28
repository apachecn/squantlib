package squantlib.model.bond

import squantlib.pricing.model._
import squantlib.model.{Market, Bond}
import squantlib.pricing.mcengine._
import squantlib.model.fx.FX
import squantlib.model.index.Index
import squantlib.model.equity.Equity

object DefaultBondSetting extends BondSetting {
  
  var productMapping:Map[String, BondSetting] = Map(
      
	  "DISC" -> NoModelSetting,
	  "SB" -> NoModelSetting,
	  "STEPUP" -> NoModelSetting,
	  
	  "JGBR10F" -> JGBRModelSetting,
	  "JGBR10N" -> JGBRModelSetting,
	  "JGBR3" -> JGBRModelSetting,
	  "JGBR5" -> JGBRModelSetting,
	      
	  "JGBM10" -> JGBMModelSetting,
	  "JGBM5" -> JGBMModelSetting,
	  "JGBM2" -> JGBMModelSetting,

	  "CALLABLE" -> SwaptionModelSetting,
	  "SUC" -> SwaptionModelSetting,
	    
	  "DUAL" -> FXBulletModelSetting,
	    
	  "DCC" -> FXCallableModelSetting,
	  "PRDC" -> FXCallableModelSetting,
	  "FXLINKED" -> FXCallableModelSetting,
	  "RDC" -> FXCallableModelSetting,
	      
	  "NKY" -> IndexMcModelSetting,
	      
	  "EB" -> EquityMcModelSetting
	  
  )
  
  def apply(bond:Bond):Unit = productMapping.get(bond.db.productid) match {
    
    case None => {}
    
    case Some(f) => f(bond)
    
  }
    
}


object NoModelSetting extends BondSetting {
  
  override def apply(bond:Bond) = {
    bond.defaultModel = (m:Market, b:Bond) => NoModel(m, b)
	bond.forceModel = false
	bond.useCouponAsYield = false
    bond.requiresCalibration = false
  }
  
}


object JGBRModelSetting extends BondSetting {
  
  override def apply(bond:Bond) = {
    bond.defaultModel = (m:Market, b:Bond) => JGBRModel(m, b)
	bond.forceModel = true
	bond.useCouponAsYield = true
	bond.requiresCalibration = false
  }
  
}


object JGBMModelSetting extends BondSetting {
  
  override def apply(bond:Bond) = {
    bond.defaultModel = (m:Market, b:Bond) => JGBMModel(m, b)
	bond.forceModel = true
	bond.useCouponAsYield = false
	bond.requiresCalibration = false
  }
  
}


object SwaptionModelSetting extends BondSetting {
  
  override def apply(bond:Bond) = {
    bond.defaultModel = (m:Market, b:Bond) => OneTimeSwaptionModel(m, b)
	bond.forceModel = true
	bond.useCouponAsYield = false
	bond.requiresCalibration = false
  }
  
}


object FXBulletModelSetting extends BondSetting {
  
  override def apply(bond:Bond) = {
    val engine = (fx:FX) => BlackScholes1f(fx)
	bond.defaultModel = (m:Market, b:Bond) => FXMontecarlo1f(m, b, engine, 100000)
	bond.forceModel = true
	bond.useCouponAsYield = false
	bond.requiresCalibration = false
  }
  
}


object FXCallableModelSetting extends BondSetting {
  
  override def apply(bond:Bond) = {
    val engine = (fx:FX) => BlackScholes1f(fx)
	bond.defaultModel = (m:Market, b:Bond) => FXMontecarlo1f(m, b, engine, 100000)
	bond.forceModel = true
	bond.useCouponAsYield = false
	bond.requiresCalibration = true
  }
  
}


object IndexMcModelSetting extends BondSetting {
  
  override def apply(bond:Bond) = {
    val engine = (index:Index) => BlackScholesWithRepo(index)
	bond.defaultModel = (m:Market, b:Bond) => IndexMontecarlo1f(m, b, engine, 50000)
	bond.forceModel = true
	bond.useCouponAsYield = false
	bond.requiresCalibration = false
  }
  
}

object EquityMcModelSetting extends BondSetting {
  
  override def apply(bond:Bond) = {
    val engine = (equity:Equity) => BlackScholesDiscreteDividends1f(equity)
	bond.defaultModel = (m:Market, b:Bond) => EquityMontecarlo1f(m, b, engine, 50000)
	bond.forceModel = true
	bond.useCouponAsYield = false
	bond.requiresCalibration = false
  }
  
}