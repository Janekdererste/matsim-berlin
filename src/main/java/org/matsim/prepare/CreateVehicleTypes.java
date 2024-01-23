package org.matsim.prepare;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.application.MATSimAppCommand;
import org.matsim.contrib.emissions.HbefaVehicleCategory;
import org.matsim.vehicles.EngineInformation;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import picocli.CommandLine;

import java.nio.file.Path;

@CommandLine.Command(
	name = "vehicle-types",
	description = "Add information to vehicles which is required for emission calculations"
)
public class CreateVehicleTypes implements MATSimAppCommand {

	@CommandLine.Option(names = "--output", description = "Path to output vehicles file.", required = true)
	private Path output;

	@Override
	public Integer call() throws Exception {

		var vehicles = VehicleUtils.createVehiclesContainer();
		var carType = createCarType();
		vehicles.addVehicleType(createRideType(carType));
		vehicles.addVehicleType(carType);
		vehicles.addVehicleType(createFreightType());

		VehicleUtils.writeVehicles(vehicles, output.toString());

		return 0;
	}

	private VehicleType createFreightType() {
		VehicleType type = VehicleUtils.createVehicleType(Id.create("freight", VehicleType.class));
		type.setDescription("Vehicle type for mode freight");
		type.setLength(15);
		type.setWidth(1.0);
		type.setFlowEfficiencyFactor(1.0);
		// 100km/h
		type.setMaximumVelocity(27.7);
		type.setNetworkMode(TransportMode.car);
		type.setPcuEquivalents(3.5);
		VehicleUtils.setHbefaVehicleCategory(type.getEngineInformation(), HbefaVehicleCategory.PASSENGER_CAR.toString());
		applyAverageHbefaCategories(type.getEngineInformation());
		return type;
	}

	private VehicleType createRideType(VehicleType carType) {
		VehicleType type = VehicleUtils.createVehicleType(Id.create("ride", VehicleType.class));
		VehicleUtils.copyFromTo(carType, type);
		type.setDescription("Vehicle type for the ride mode. Copied from car mode");
		VehicleUtils.setHbefaVehicleCategory(type.getEngineInformation(), HbefaVehicleCategory.NON_HBEFA_VEHICLE.toString());
		applyAverageHbefaCategories(type.getEngineInformation());
		return type;
	}

	private static VehicleType createCarType() {
		VehicleType type = VehicleUtils.createVehicleType(Id.create("car", VehicleType.class));
		type.setDescription("Vehicle type for mode car");
		type.setLength(7.5);
		type.setWidth(1.0);
		type.setFlowEfficiencyFactor(1.0);
		// 130km/h
		type.setMaximumVelocity(36.1);
		type.setNetworkMode(TransportMode.car);
		type.setPcuEquivalents(1.0);
		VehicleUtils.setHbefaVehicleCategory(type.getEngineInformation(), HbefaVehicleCategory.HEAVY_GOODS_VEHICLE.toString());
		applyAverageHbefaCategories(type.getEngineInformation());
		return type;
	}

	private static void applyAverageHbefaCategories(EngineInformation ei) {

		VehicleUtils.setHbefaTechnology(ei, "average");
		VehicleUtils.setHbefaEmissionsConcept(ei, "average");
		VehicleUtils.setHbefaSizeClass(ei, "average");
	}
}
