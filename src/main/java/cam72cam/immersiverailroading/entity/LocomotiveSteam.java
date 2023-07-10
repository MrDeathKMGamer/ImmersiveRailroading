package cam72cam.immersiverailroading.entity;

import cam72cam.immersiverailroading.Config;
import cam72cam.immersiverailroading.Config.ConfigBalance;
import cam72cam.immersiverailroading.Config.ImmersionConfig;
import cam72cam.immersiverailroading.inventory.SlotFilter;
import cam72cam.immersiverailroading.library.GuiTypes;
import cam72cam.immersiverailroading.library.ModelComponentType;
import cam72cam.immersiverailroading.library.Permissions;
import cam72cam.immersiverailroading.model.part.Control;
import cam72cam.immersiverailroading.registry.LocomotiveSteamDefinition;
import cam72cam.immersiverailroading.util.BurnUtil;
import cam72cam.immersiverailroading.util.FluidQuantity;
import cam72cam.immersiverailroading.util.LiquidUtil;
import cam72cam.immersiverailroading.util.Speed;
import cam72cam.mod.entity.Player;
import cam72cam.mod.entity.sync.TagSync;
import cam72cam.mod.fluid.Fluid;
import cam72cam.mod.fluid.FluidStack;
import cam72cam.mod.item.ItemStack;
import cam72cam.mod.math.Vec3d;
import cam72cam.mod.serialization.TagCompound;
import cam72cam.mod.serialization.TagField;
import cam72cam.mod.serialization.TagMapper;

import java.util.*;
import java.util.stream.Collectors;

public class LocomotiveSteam extends Locomotive {
	// PSI
	@TagSync
	@TagField("boiler_psi")
	private float boilerPressure = 0;

	// Celsius
	@TagSync
	@TagField("boiler_temperature")
	private float boilerTemperature;

	@TagSync
	@TagField("pressure_valve")
	private boolean pressureValve = false;
	
	// Map<Slot, TicksToBurn>
	@TagSync
	@TagField(value = "burn_time", mapper = LocomotiveSteam.SlotTagMapper.class)
	private Map<Integer, Integer> burnTime = new HashMap<>();
	@TagSync
	@TagField(value = "burn_max", mapper = LocomotiveSteam.SlotTagMapper.class)
	private Map<Integer, Integer> burnMax = new HashMap<>();

	private float drainRemainder;
	
	public LocomotiveSteam() {
		boilerTemperature = ambientTemperature();
	}

	@Override
	public LocomotiveSteamDefinition getDefinition() {
		return super.getDefinition(LocomotiveSteamDefinition.class);
	}

	@Override
	public boolean openGui(Player player) {
		if (!getDefinition().isCabCar() && player.hasPermission(Permissions.LOCOMOTIVE_CONTROL)) {
			GuiTypes.STEAM_LOCOMOTIVE.open(player, this);
			return true;
		}
		return false;
	}

	public float getBoilerTemperature() {
		return boilerTemperature;
	}
	private void setBoilerTemperature(float temp) {
		boilerTemperature = temp;
	}
	
	public float getBoilerPressure() {
		return boilerPressure;
	}
	private void setBoilerPressure(float temp) {
		boilerPressure = temp;
	}

	public Map<Integer, Integer> getBurnTime() {
		return burnTime;
	}
	public Map<Integer, Integer> getBurnMax() {
		return burnMax;
	}
	
	//make properties local variables for readability
	//initialized on first tick to avoid NPE
	private int maxPower_Hp = 0;
	private int maxTractiveEffort_N = 0;
	private double ratedTopSpeed_Km_H = 0;
	private int mawp = 0;	//maximum allowable working pressure, ie max boiler pressure
	private int reverserDirection = 0;
	private double cutoffTractiveEffort_N = 0;	//maximum tractive effort with current cutoff setting and boiler pressure in N, assuming full steam demand is met
	private double steamDemand_Hp = 0;			//current steam demand in horse power, based on cutoff position and current speed
	private double maxSteamFlow_Hp = 0;			//current maximum steam flow through the regulator in horse power, steam use cannot exceed this regardless of demand
	
	//used once per tick to calculate cutoffTractiveEffort_N
	private float cutoffTractiveEffort() {
		//tractive effort in N produced by each PSI applied to the cylinders
		float tePerPSI = maxTractiveEffort_N / mawp;
		/*
		 * average pressure on the cylinders over the course of the stroke,
		 * calculated by assuming current boiler pressure for length of valve travel(cutoff setting)
		 * followed by finding pressure after expansion over the rest of the stroke and averaging it with the boiler pressure
		 * this gives the average pressure over the remainder of the stroke
		 * multiply these pressures by the portion of the stroke to which they are applicable and add to get final average pressure
		 */
		float averagePreasure = (getBoilerPressure() * getReverser()) + ((((getBoilerPressure() * reverserDirection) + (getBoilerPressure() * getReverser())) / 2) * (1 - Math.abs(getReverser())));
		return tePerPSI * averagePreasure;
	}
	
	//backpressure opposing motion due to high cutoff at high speed, produces a scalar as a proportion of maximum tractive effort
	private double backpressure(double speedPercent) {
		return Math.min(
			2.0d, 	//limit maximum backpressure to max tractive effort in reverse
			Math.max(
				0, //formula spends a lot of time negative, so limit minimum to 0
				//formula estimated and tweaked in desmos until it did about what I want
				((.55d * speedPercent) * (Math.abs(getReverser()) * Math.pow(2.0d * Math.abs(getReverser()), 2.0d * speedPercent))) - 0.21d) 
			) * (getBoilerPressure() / mawp);	//scale to current boiler pressure
	}
	
	@Override
	public double getAppliedTractiveEffort(Speed speed) {
		if (getDefinition().isCabCar()) {
			return 0;
		}
		if(ImmersionConfig.arcadePhysics) {//use arcade physics
			double traction_N = this.getDefinition().getStartingTractionNewtons(gauge);
			if (Config.isFuelRequired(gauge)) {
				traction_N = traction_N / this.getDefinition().getMaxPSI(gauge) * this.getBoilerPressure();
			}

			// Cap the max "effective" reverser.  At high speeds having a fully open reverser just damages equipment
			double reverser = getReverser();
			double reverserCap = 0.5;
			double maxReverser = 1 - Math.abs(getCurrentSpeed().metric()) / getDefinition().getMaxSpeed(gauge).metric() * reverserCap;

			// This should probably be tuned...
			double multiplier = Math.copySign(Math.abs(Math.pow(getThrottle() * Math.min(Math.abs(reverser), maxReverser), 3)), reverser);

			return traction_N * multiplier;
		} else {							//use realistic physics
			double speedPercent = Math.abs(getCurrentSpeed().metric() / (double)ratedTopSpeed_Km_H);	//current speed as a percent of rated top speed
			
			double workingTractiveEffort;	//working tractive effort calculated with current steam flow, cutoff demand, and boiler pressure, before factoring loss due to backpressure
			if(steamDemand_Hp < maxSteamFlow_Hp) {
				workingTractiveEffort = cutoffTractiveEffort_N;
			}else {
				workingTractiveEffort = cutoffTractiveEffort_N * (maxSteamFlow_Hp / steamDemand_Hp);
			}
			
			return (workingTractiveEffort - ((backpressure(speedPercent) * (double)maxTractiveEffort_N) * reverserDirection)) * ConfigBalance.tractionMultiplier;
		}
	}
	
	@Override
	public void onDissassemble() {
		super.onDissassemble();
		this.setBoilerTemperature(ambientTemperature());
		this.setBoilerPressure(0);
		
		for (Integer slot : burnTime.keySet()) {
			burnTime.put(slot, 0);
		}
	}

	@Override
	public double getTractiveEffortNewtons(Speed speed) {
		return (getDefinition().cab_forward ? -1 : 1) * super.getTractiveEffortNewtons(speed);
	}

	@Override
	protected double simulateWheelSlip() {
		return (getDefinition().cab_forward ? -1 : 1) * super.simulateWheelSlip();
	}


	@Override
	public void onTick() {
		super.onTick();

		if (getWorld().isClient) {
			return;
		}

		if (this.getTickCount() < 2) {
			//initialize constant values
			maxPower_Hp = this.getDefinition().getHorsePower(gauge);
			maxTractiveEffort_N = this.getDefinition().getStartingTractionNewtons(gauge);
			ratedTopSpeed_Km_H = this.getDefinition().getMaxSpeed(gauge).metric();
			mawp = this.getDefinition().getMaxPSI(gauge);
			// Prevent explosions
			return;
		}
		
		//calculate values used multiple times once per tick
		reverserDirection = getReverser() <= 0 ? -1 : 1;
		cutoffTractiveEffort_N = cutoffTractiveEffort();
		steamDemand_Hp = Math.abs(cutoffTractiveEffort_N * getCurrentSpeed().metric()) / 2684.52d; 
			//2684.52 is combined conversion factor of km/h to m/s and Watts to hp
		maxSteamFlow_Hp = (double)maxPower_Hp * getThrottle() * 1.5d * (getBoilerPressure() / (double)mawp);
		

		OptionalDouble control = this.getDefinition().getModel().getControls().stream()
				.filter(x -> x.part.type == ModelComponentType.WHISTLE_CONTROL_X)
				.mapToDouble(this::getControlPosition)
				.max();
		if (control.isPresent() && control.getAsDouble() > 0) {
			this.setHorn(10, hornPlayer);
		}

		if (!this.isBuilt() || getDefinition().isCabCar()) {
			return;
		}

		EntityCoupleableRollingStock stock = this;
		CouplerType coupler = getDefinition().cab_forward ? CouplerType.FRONT : CouplerType.BACK;
		while (coupler != null && stock.getCoupled(coupler) instanceof Tender) {
			Tender tender = (Tender) stock.getCoupled(coupler);

			// Only drain 10mb at a time from the tender
			int desiredDrain = 10;
			if (getTankCapacity().MilliBuckets() - getServerLiquidAmount() >= 10) {
				theTank.drain(tender.theTank, desiredDrain, false);
			}

			if (this.getTickCount() % 20 == 0 && this.getDefinition().tender_auto_feed) {
				// Top off stacks
				for (int slot = 2; slot < this.cargoItems.getSlotCount(); slot ++) {
					if (BurnUtil.getBurnTime(this.cargoItems.get(slot)) != 0) {
						for (int tenderSlot = 0; tenderSlot < tender.cargoItems.getSlotCount(); tenderSlot ++) {
							if (this.cargoItems.get(slot).is(tender.cargoItems.get(tenderSlot))) {
								if (this.cargoItems.get(slot).getLimit() > this.cargoItems.get(slot).getCount()) {
									ItemStack extracted = tender.cargoItems.extract(tenderSlot, 1, false);
									this.cargoItems.insert(slot, extracted, false);
								}
							}
						}
					}
				}
			}
			coupler = tender.getCouplerFor(stock);
			if (coupler == null) {
				break;
			}
			coupler = coupler.opposite();
			stock = tender;
		}
		
		float boilerTemperature = getBoilerTemperature();
		float boilerPressure = getBoilerPressure();
		float waterLevelMB = this.getLiquidAmount();
		int burningSlots = 0;
		float waterUsed = 0;

		if (boilerPressure < 0) {
			boilerPressure = 0;
		}
		
		if (this.getLiquidAmount() > 0) {
			for (int slot = 2; slot < this.cargoItems.getSlotCount(); slot ++) {
				int remainingTime = burnTime.getOrDefault(slot, 0);
				if (remainingTime <= 0) {
					ItemStack stack = this.cargoItems.get(slot);
					if (stack.getCount() <= 0 || BurnUtil.getBurnTime(stack) == 0) {
						continue;
					}
					remainingTime = (int) (BurnUtil.getBurnTime(stack) /gauge.scale() * (Config.ConfigBalance.locoSteamFuelEfficiency / 100.0));
					burnTime.put(slot, remainingTime);
					burnMax.put(slot, remainingTime);
					stack.setCount(stack.getCount()-1);
					this.cargoItems.set(slot, stack);
				} else {
					burnTime.put(slot, remainingTime - 1);
				}
				burningSlots += 1;
			}
		}
		
		double energyKCalDeltaTick = 0;
		
		if (burningSlots != 0 && this.getLiquidAmount() > 0) {
			energyKCalDeltaTick += burningSlots * coalEnergyKCalTick() * 1.05;
		}

		// Assume the boiler is a cube...
		double boilerVolume = this.getTankCapacity().Buckets();
		double boilerEdgeM = Math.pow(boilerVolume, 1.0/3.0);
		double boilerAreaM = 6 * Math.pow(boilerEdgeM, 2);

		if (boilerTemperature > 0) {
			// Decrease temperature due to heat loss
			// Estimate Kw emitter per m^2: (TdegC/10)^2 / 100
			// TODO consider ambientTemperature
			double radiatedKwHr = Math.pow(boilerTemperature/10, 2) / 100 * boilerAreaM * 2;
			double radiatedKCalHr = radiatedKwHr * 859.85;
			double radiatedKCalTick = radiatedKCalHr / 60 / 60 / 20 * ConfigBalance.locoHeatTimeScale;
			energyKCalDeltaTick -= radiatedKCalTick / 1000;
		}
		
		if (energyKCalDeltaTick != 0) {
			// Change temperature
			// 1 KCal raises 1KG water at STP 1 degree
			// 1 KG of water == 1 m^3 of water 
			// TODO what happens when we change liters per mb FluidQuantity.FromMillibuckets((int) waterLevelMB).Liters()
			//  +1 prevents div by zero
			boilerTemperature += energyKCalDeltaTick / ((waterLevelMB + 1) / 1000);
		}
		
		if (boilerTemperature > 100) {
			// Assume linear relationship between temperature and pressure
			float heatTransfer = boilerTemperature - 100;
			boilerPressure += heatTransfer;

			if (this.getPercentLiquidFull() > 25) {
				boilerTemperature -= heatTransfer;
			}
			
			// Pressure relief valve
			int maxPSI = this.getDefinition().getMaxPSI(gauge);
			pressureValve = boilerPressure > maxPSI;
			if (boilerPressure > maxPSI) {
				waterUsed += boilerPressure - maxPSI;
				boilerPressure = maxPSI;
			}
		} else {
			if (boilerPressure > 0) {
				// Reduce pressure by needed temperature
				boilerPressure = Math.max(0, boilerPressure - (100 - boilerTemperature));
				boilerTemperature = 100;
			}

			pressureValve = false;
		}
		
		double throttle;
		if(steamDemand_Hp < maxSteamFlow_Hp) {
			//steam use is steam demand
			throttle = steamDemand_Hp * 0.17811d * ConfigBalance.locoHeatTimeScale;
		}else {
			//steam use is max flow
			throttle = maxSteamFlow_Hp * 0.17811d * ConfigBalance.locoHeatTimeScale;
		}
		if (throttle != 0 && boilerPressure > 0) {
			/*double burnableSlots = this.cargoItems.getSlotCount()-2;
			double maxKCalTick = burnableSlots * coalEnergyKCalTick();
			double maxPressureTick = maxKCalTick / (this.getTankCapacity().MilliBuckets() / 1000);
			maxPressureTick = maxPressureTick * 0.8; // 20% more pressure gen energyCapability to balance heat loss*/
			
			float delta = (float) (throttle  / (this.getTankCapacity().MilliBuckets() / 1000));
			
			boilerPressure = Math.max(0, boilerPressure - delta);
			waterUsed += delta;
		}
		
		if (waterUsed != 0) {
			waterUsed *= Config.ConfigBalance.locoWaterUsage;
			waterUsed += drainRemainder;
			if (waterUsed > 0 && theTank.getContents() != null) {
				theTank.drain(new FluidStack(theTank.getContents().getFluid(), (int) Math.floor(waterUsed)), false);
				drainRemainder = waterUsed % 1;
			}
		}
		
		setBoilerPressure(boilerPressure);
		setBoilerTemperature(Math.max(boilerTemperature, ambientTemperature()));

		if (boilerPressure > this.getDefinition().getMaxPSI(gauge) * 1.1 || (boilerPressure > this.getDefinition().getMaxPSI(gauge) * 0.5 && boilerTemperature > 150)) {
			// 10% over max pressure OR
			// Half max pressure and high boiler temperature
			//EXPLODE

			Vec3d pos = this.getPosition();
			if (Config.ConfigDamage.explosionsEnabled) {
				this.createExplosion(pos, boilerPressure/5, Config.ConfigDamage.explosionEnvDamageEnabled);
			}
			getWorld().removeEntity(this);
		}
	}

	@Override
	public boolean providesElectricalPower() {
		return getBoilerPressure() > 0 || !ConfigBalance.FuelRequired;
	}

	@Override
    public boolean internalLightsEnabled() {
		return getBoilerPressure() > 0 || !ConfigBalance.FuelRequired || super.internalLightsEnabled();
    }

    @Override
	public void onDrag(Control<?> component, double newValue) {
		super.onDrag(component, newValue);

		if (component.part.type == ModelComponentType.WHISTLE_CONTROL_X) {
			this.setHorn(10, null);
		}
	}

	@Override
	public void onDragRelease(Control<?> component) {
		super.onDragRelease(component);
		if (component.part.type == ModelComponentType.WHISTLE_CONTROL_X) {
			this.setControlPosition(component, 0);
		}
	}

	@Override
	protected void initContainerFilter() {
		cargoItems.filter.clear();
		this.cargoItems.filter.put(0, SlotFilter.FLUID_CONTAINER);
		this.cargoItems.filter.put(1, SlotFilter.FLUID_CONTAINER);
		this.cargoItems.defaultFilter = SlotFilter.BURNABLE;
	}

	@Override
	public int getInventorySize() {
		return this.getDefinition().getInventorySize(gauge) + 2;
	}

	@Override
	public int getInventoryWidth() {
		return this.getDefinition().getInventoryWidth(gauge);
	}
	
	@Override
	protected int[] getContainerInputSlots() {
		return new int[] { 0 };
	}
	@Override
	protected int[] getContainertOutputSlots() {
		return new int[] { 1 };
	}

	@Override
	public FluidQuantity getTankCapacity() {
		return this.getDefinition().getTankCapacity(gauge);
	}

	@Override
	public List<Fluid> getFluidFilter() {
		return LiquidUtil.getWater();
	}

	public boolean isOverpressure() {
		return pressureValve;
	}

	private double coalEnergyKCalTick() {
		// Coal density = 800 KG/m3 (engineering toolbox)
		/*double coalEnergyDensity = 30000; // KJ/KG (engineering toolbox)
		double coalEnergyKJ = coalEnergyDensity / 9; // Assume each slot is burning 1/9th of a coal block
		double coalEnergyBTU = coalEnergyKJ * 0.958; // 1 KJ = 0.958 BTU
		double coalEnergyKCal = coalEnergyBTU / (3.968 * 1000); // 3.968 BTU = 1 KCal
		double coalBurnTicks = 1600; // This is a bit of fudge
		return coalEnergyKCal / coalBurnTicks * ConfigBalance.locoHeatTimeScale;*/
		//redefine based on max horsepower rating to limit max steam production and aproximate thermal efficiency
		return (maxPower_Hp / (getInventorySize() - 2)) * 0.17811d * ConfigBalance.locoHeatTimeScale;
	}

	private static class SlotTagMapper implements TagMapper<Map<Integer, Integer>> {
		@Override
		public TagAccessor<Map<Integer, Integer>> apply(Class<Map<Integer, Integer>> type, String fieldName, TagField tag) {
			return new TagAccessor<>(
					(d, o) -> d.setMap(fieldName, o, Objects::toString, i -> new TagCompound().setInteger("val", i)),
					d -> d.getMap(fieldName, Integer::parseInt, t -> {
						Integer val = t.getInteger("val");
						if (val == null) {
							val = 0;
						}
						return val;
					})
			);
		}
	}

	public boolean cylinderDrainsEnabled() {
		// This could be optimized to once-per-tick, but I'm not sure that is necessary
		List<Control<?>> drains = getDefinition().getModel().getControls().stream().filter(x -> x.part.type == ModelComponentType.CYLINDER_DRAIN_CONTROL_X).collect(Collectors.toList());
		if (drains.isEmpty()) {
			double csm = Math.abs(getCurrentSpeed().metric()) / gauge.scale();
			return csm < 20;
		}

		return drains.stream().anyMatch(c -> getControlPosition(c) == 1);
	}
}
