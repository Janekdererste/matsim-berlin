package org.matsim.prepare;

import com.beust.jcommander.Parameter;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.CRS;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.emissions.HbefaVehicleCategory;
import org.matsim.contrib.emissions.OsmHbefaMapping;
import org.matsim.contrib.gtfs.RunGTFS2MATSim;
import org.matsim.contrib.osm.networkReader.LinkProperties;
import org.matsim.contrib.osm.networkReader.SupersonicOsmNetworkReader;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.NetworkCleaner;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import org.matsim.core.utils.gis.ShapeFileReader;
import org.matsim.core.utils.io.tabularFileParser.TabularFileHandler;
import org.matsim.core.utils.io.tabularFileParser.TabularFileParser;
import org.matsim.core.utils.io.tabularFileParser.TabularFileParserConfig;
import org.matsim.pt.transitSchedule.api.*;
import org.matsim.pt.utils.CreatePseudoNetwork;
import org.matsim.vehicles.*;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.operation.TransformException;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class PrepareAgentEmissionNetwork {

	private static final String countNodesMapping = "studies/countries/de/open_berlin_scenario/be_3/counts/counts_OSM-nodes.csv";
	private static final String osmFile = "projects/mosaik-2/raw-data/osm/berlin-brandenburg-20240116.osm.pbf";
	private static final String stateShapeFile = "projects/mosaik-2/raw-data/shapes/bundeslaender/VG250_LAN.shp";

	private static final String bboxShapeFile = "/Users/janek/Documents/palm/agent_emissions/berlin-v2.0/domain-epsg25833.shp";
	private static final String gtfsFile = "matsim/scenarios/countries/de/berlin/berlin-v5.5-10pct/original-data/GTFS-VBB-20181214/GTFS-VBB-20181214.zip";

	public static final String networkOutputFile = "projects/mosaik-2/matsim-input-files/berlin/berlin-6.0-network-with-geometries.xml.gz";
	public static final String scheduleOutputFile = "projects/mosaik-2/matsim-input-files/berlin/berlin-6.0-schedule-with-geometries.xml.gz";
	public static final String vehiclesOutputFile ="projects/mosaik-2/matsim-input-files/berlin/berlin-6.0-transit-vehicles-with-geometries.xml.gz";

	private static final CoordinateTransformation transformation = TransformationFactory.getCoordinateTransformation(TransformationFactory.WGS84,"EPSG:25833");
	private static final Set<String> networkModes = Set.of(TransportMode.car, TransportMode.ride, "freight");
	private static final Logger log = LogManager.getLogger();

	public static void main(String[] args) {

		if (args.length != 2) throw new IllegalArgumentException("shared svn path and public svn path must be provided");

		var svnArg = new Input();
		svnArg.sharedSvn = args[0];
		svnArg.publicSvn = args[1];
		var network = createNetwork(svnArg);

		//------------- also create pt --------------------------

		var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		createSchedule(scenario, svnArg);

		new CreatePseudoNetwork(scenario.getTransitSchedule(), network, "pt_", 0.1, 100000).createNetwork();

		createVehicles(scenario, network);

		new NetworkWriter(network).write(Paths.get(svnArg.sharedSvn).resolve(networkOutputFile).toString());
		new TransitScheduleWriter(scenario.getTransitSchedule()).writeFile(Paths.get(svnArg.sharedSvn).resolve(scheduleOutputFile).toString());
		new MatsimVehicleWriter(scenario.getTransitVehicles()).writeFile(Paths.get(svnArg.sharedSvn).resolve(vehiclesOutputFile).toString());
	}

	private static Network createNetwork(Input svnArg){
		var svnPath = Paths.get(svnArg.sharedSvn);
		var nodeIdsToKeep = readNodeIds(svnPath.resolve(countNodesMapping));

		var berlin = getBerlinShape(svnPath.resolve(stateShapeFile));

		var network = new SupersonicOsmNetworkReader.Builder()
			.setCoordinateTransformation(transformation)
			.setPreserveNodeWithId(nodeIdsToKeep::contains)
			.setStoreOriginalGeometry(true)
			.setIncludeLinkAtCoordWithHierarchy((coord, hierarchy) -> {

				// the entire scenario has tertiary streets
				if (hierarchy <= LinkProperties.LEVEL_TERTIARY) return true;

				// city of berlin has all the streets
				return berlin.covers(MGC.coord2Point(coord));
			})
			.setAfterLinkCreated((link, map, direction) -> link.setAllowedModes(networkModes))
			.build()
			.read(Paths.get(svnArg.sharedSvn).resolve(osmFile));

		new NetworkCleaner().run(network);

		useOriginalGeometryWithinGeometry(network, loadStudyArea(Paths.get(bboxShapeFile)));
		network.getAttributes().putAttribute("coordinateReferenceSystem", "EPSG:25833");
		OsmHbefaMapping.build().addHbefaMappings(network);
		return network;
	}

	private static void useOriginalGeometryWithinGeometry(Network network, PreparedGeometry bbox) {


		// if links are within the bounding box and if their original geometry is more complex than
		// the current link, mark them for being replaced
		var linksToReplace = network.getLinks().values().stream()
			.filter(link -> isCoveredBy(link, bbox))
			.filter(link -> NetworkUtils.getOriginalGeometry(link).size() > 2)
			.toList();

		// add all the nodes which we need for the enhanced geometries
		linksToReplace.stream()
			.flatMap(link -> NetworkUtils.getOriginalGeometry(link).stream())
			.filter(node -> !network.getNodes().containsKey(node.getId()))
			.forEach(network::addNode);

		// now, add the links in between the original geometries
		var linksToAdd = linksToReplace.stream()
			.flatMap(link -> {

				var originalGeometry = NetworkUtils.getOriginalGeometry(link);
				var attributes = link.getAttributes().getAsMap();

				// for each node in the original geometry add one link end at second to last node
				List<Link> newLinks = new ArrayList<>();
				for (int i = 0; i < originalGeometry.size() - 1; i++) {

					// all the nodes are in the network already. fetch them from the network via id, so that we pass
					// the correct object reference to the newLink
					var from = network.getNodes().get(originalGeometry.get(i).getId());
					var to = network.getNodes().get(originalGeometry.get(i + 1).getId());
					var newLink = network.getFactory().createLink(Id.createLinkId(link.getId().toString() + "_" + i), from, to);

					// copy all values of the original link
					newLink.setAllowedModes(link.getAllowedModes());
					newLink.setCapacity(link.getCapacity());
					newLink.setFreespeed(link.getFreespeed());
					newLink.setNumberOfLanes(link.getNumberOfLanes());

					// copy all unstructured attributes
					attributes.forEach((key, value) -> newLink.getAttributes().putAttribute(key, value));

					newLinks.add(newLink);
				}
				return newLinks.stream();
			})
			.toList();

		// now delete the links simplified links
		for (var link : linksToReplace) {
			network.removeLink(link.getId());
		}

		// add the new links to the network
		for (var link : linksToAdd) {
			network.addLink(link);
		}
	}

	private static PreparedGeometry getBerlinShape(Path stateShapeFile) {

		var berlinGeometry = ShapeFileReader.getAllFeatures(stateShapeFile.toString()).stream()
			.filter(feature -> feature.getAttribute("GEN").equals("Berlin"))
			.map(feature -> (Geometry)feature.getDefaultGeometry())
			.findAny()
			.orElseThrow();

		try {
			var sourceCRS = CRS.decode("EPSG:25832");
			var targetCRS = CRS.decode("EPSG:25833");

			var mathTransform = CRS.findMathTransform(sourceCRS, targetCRS);
			var transformed = JTS.transform(berlinGeometry, mathTransform);
			var geometryFactory = new PreparedGeometryFactory();
			return geometryFactory.create(transformed);
		} catch (FactoryException | TransformException e) {
			throw new RuntimeException(e);
		}
	}

	private static PreparedGeometry loadStudyArea(Path shapeFile) {

		var geometry = ShapeFileReader.getAllFeatures(shapeFile.toString()).stream()
			.map(SimpleFeature::getDefaultGeometry)
			.map(o -> (Geometry)o)
			.findFirst()
			.orElseThrow();
		var geometryFactory = new PreparedGeometryFactory();
		return geometryFactory.create(geometry);
	}

	/*private static PreparedGeometry createStudyArea() {

		var geometry = new GeometryFactory().createPolygon(
			new Coordinate[]{
				new Coordinate(385030.5, 5818413.0), new Coordinate(385030.5, 5820459),
				new Coordinate(387076.5, 5820459), new Coordinate(385030.5, 5820459),
				new Coordinate(385030.5, 5818413.0)
			}
		);
		return new PreparedGeometryFactory().create(geometry);
	}
	 */

	private static boolean isCoveredBy(Link link, PreparedGeometry geometry) {
		return geometry.covers(MGC.coord2Point(link.getFromNode().getCoord()))
			|| geometry.covers(MGC.coord2Point(link.getToNode().getCoord()));
	}

	private static void createSchedule(Scenario scenario, Input svnArgs) {
		LocalDate date = LocalDate.parse("2018-12-20");
		RunGTFS2MATSim.convertGTFSandAddToScenario(scenario, Paths.get(svnArgs.publicSvn).resolve(gtfsFile).toString(), date, transformation, false, false);
	}

	private static void createVehicles(Scenario scenario, Network network){
		// create TransitVehicle types
		// see https://svn.vsp.tu-berlin.de/repos/public-svn/publications/vspwp/2014/14-24/ for veh capacities
		// the values set here are at the upper end of the typical capacity range, so on lines with high capacity vehicles the
		// capacity of the matsim vehicle equals roughly the real vehicles capacity and on other lines the Matsim vehicle
		// capacity is higher than the real used vehicle's capacity (gtfs provides no information on which vehicle type is used,
		// and this would be beyond scope here). - gleich sep'19
		VehiclesFactory vehicleFactory = scenario.getVehicles().getFactory();

		VehicleType reRbVehicleType = vehicleFactory.createVehicleType( Id.create( "RE_RB_veh_type", VehicleType.class ) );
		{
			VehicleCapacity capacity = reRbVehicleType.getCapacity();
			capacity.setSeats( 500 );
			capacity.setStandingRoom( 600 );
			VehicleUtils.setDoorOperationMode(reRbVehicleType, VehicleType.DoorOperationMode.serial); // first finish boarding, then start alighting
			VehicleUtils.setAccessTime(reRbVehicleType, 1.0 / 10.0); // 1s per boarding agent, distributed on 10 doors
			VehicleUtils.setEgressTime(reRbVehicleType, 1.0 / 10.0); // 1s per alighting agent, distributed on 10 doors
			scenario.getTransitVehicles().addVehicleType( reRbVehicleType );
		}
		VehicleType sBahnVehicleType = vehicleFactory.createVehicleType( Id.create( "S-Bahn_veh_type", VehicleType.class ) );
		{
			VehicleCapacity capacity = sBahnVehicleType.getCapacity();
			capacity.setSeats( 400 );
			capacity.setStandingRoom( 800 );
			VehicleUtils.setDoorOperationMode(sBahnVehicleType, VehicleType.DoorOperationMode.serial); // first finish boarding, then start alighting
			VehicleUtils.setAccessTime(sBahnVehicleType, 1.0 / 24.0); // 1s per boarding agent, distributed on 8*3 doors
			VehicleUtils.setEgressTime(sBahnVehicleType, 1.0 / 24.0); // 1s per alighting agent, distributed on 8*3 doors
			scenario.getTransitVehicles().addVehicleType( sBahnVehicleType );
		}
		VehicleType uBahnVehicleType = vehicleFactory.createVehicleType( Id.create( "U-Bahn_veh_type", VehicleType.class ) );
		{
			VehicleCapacity capacity = uBahnVehicleType.getCapacity() ;
			capacity.setSeats( 300 );
			capacity.setStandingRoom( 600 );
			VehicleUtils.setDoorOperationMode(uBahnVehicleType, VehicleType.DoorOperationMode.serial); // first finish boarding, then start alighting
			VehicleUtils.setAccessTime(uBahnVehicleType, 1.0 / 18.0); // 1s per boarding agent, distributed on 6*3 doors
			VehicleUtils.setEgressTime(uBahnVehicleType, 1.0 / 18.0); // 1s per alighting agent, distributed on 6*3 doors
			scenario.getTransitVehicles().addVehicleType( uBahnVehicleType );
		}
		VehicleType tramVehicleType = vehicleFactory.createVehicleType( Id.create( "Tram_veh_type", VehicleType.class ) );
		{
			VehicleCapacity capacity = tramVehicleType.getCapacity() ;
			capacity.setSeats( 80 );
			capacity.setStandingRoom( 170 );
			VehicleUtils.setDoorOperationMode(tramVehicleType, VehicleType.DoorOperationMode.serial); // first finish boarding, then start alighting
			VehicleUtils.setAccessTime(tramVehicleType, 1.0 / 5.0); // 1s per boarding agent, distributed on 5 doors
			VehicleUtils.setEgressTime(tramVehicleType, 1.0 / 5.0); // 1s per alighting agent, distributed on 5 doors
			scenario.getTransitVehicles().addVehicleType( tramVehicleType );
		}
		VehicleType busVehicleType = vehicleFactory.createVehicleType( Id.create( "Bus_veh_type", VehicleType.class ) );
		{
			VehicleCapacity capacity = busVehicleType.getCapacity() ;
			capacity.setSeats( 50 );
			capacity.setStandingRoom( 100 );
			VehicleUtils.setDoorOperationMode(busVehicleType, VehicleType.DoorOperationMode.serial); // first finish boarding, then start alighting
			VehicleUtils.setAccessTime(busVehicleType, 1.0 / 3.0); // 1s per boarding agent, distributed on 3 doors
			VehicleUtils.setEgressTime(busVehicleType, 1.0 / 3.0); // 1s per alighting agent, distributed on 3 doors
			scenario.getTransitVehicles().addVehicleType( busVehicleType );
		}
		VehicleType ferryVehicleType = vehicleFactory.createVehicleType( Id.create( "Ferry_veh_type", VehicleType.class ) );
		{
			VehicleCapacity capacity = ferryVehicleType.getCapacity() ;
			capacity.setSeats( 100 );
			capacity.setStandingRoom( 100 );
			VehicleUtils.setDoorOperationMode(ferryVehicleType, VehicleType.DoorOperationMode.serial); // first finish boarding, then start alighting
			VehicleUtils.setAccessTime(ferryVehicleType, 1.0); // 1s per boarding agent, distributed on 1 door
			VehicleUtils.setEgressTime(ferryVehicleType, 1.0); // 1s per alighting agent, distributed on 1 door
			scenario.getTransitVehicles().addVehicleType( ferryVehicleType );
		}
		// set link speeds and create vehicles according to pt mode
		for (TransitLine line: scenario.getTransitSchedule().getTransitLines().values()) {
			VehicleType lineVehicleType;
			String stopFilter = "";

			// identify veh type / mode using gtfs route type (3-digit code, also found at the end of the line id (gtfs: route_id))
			int gtfsTransitType;
			try {
				gtfsTransitType = Integer.parseInt( (String) line.getAttributes().getAttribute("gtfs_route_type"));
			} catch (NumberFormatException e) {
				log.error("unknown transit mode! Line id was " + line.getId().toString() +
					"; gtfs route type was " + line.getAttributes().getAttribute("gtfs_route_type"));
				throw new RuntimeException("unknown transit mode");
			}

            switch (gtfsTransitType) {
                // the vbb gtfs file generally uses the new gtfs route types, but some lines use the old enum in the range 0 to 7
                // see https://sites.google.com/site/gtfschanges/proposals/route-type
                // and https://developers.google.com/transit/gtfs/reference/#routestxt
                // In GTFS-VBB-20181214.zip some RE lines are wrongly attributed as type 700 (bus)!

                // freespeed are set to make sure that no transit service is delayed
                // and arrivals are as punctual (not too early) as possible
                case 100 -> {
                    lineVehicleType = reRbVehicleType;
                    stopFilter = "station_S/U/RE/RB";
                }
                case 109 -> {
                    // S-Bahn-Berlin is agency id 1
                    lineVehicleType = sBahnVehicleType;
                    stopFilter = "station_S/U/RE/RB";
                }
                case 400 -> {
                    lineVehicleType = uBahnVehicleType;
                    stopFilter = "station_S/U/RE/RB";
                } // bus, same as 700
                case 3, 700 ->
                    // BVG is agency id 796
                        lineVehicleType = busVehicleType;
                case 900 -> lineVehicleType = tramVehicleType;
                case 1000 -> lineVehicleType = ferryVehicleType;
                default -> {
                    log.error("unknown transit mode! Line id was " + line.getId().toString() +
                            "; gtfs route type was " + line.getAttributes().getAttribute("gtfs_route_type"));
                    throw new RuntimeException("unknown transit mode");
                }
            }

			for (TransitRoute route: line.getRoutes().values()) {
				int routeVehId = 0; // simple counter for vehicle id _per_ TransitRoute

				// increase speed if current freespeed is lower.
				List<TransitRouteStop> routeStops = route.getStops();
				if (routeStops.size() < 2) {
					log.error("TransitRoute with less than 2 stops found: line " + line.getId().toString() +
						", route " + route.getId().toString());
					throw new RuntimeException("");
				}

				double lastDepartureOffset = route.getStops().get(0).getDepartureOffset().seconds();
				// min. time spend at a stop, useful especially for stops whose arrival and departure offset is identical,
				// so we need to add time for passengers to board and alight
				double minStopTime = 30.0;

				for (int i = 1; i < routeStops.size(); i++) {
					TransitRouteStop routeStop = routeStops.get(i);
					// if there is no departure offset set (or infinity), it is the last stop of the line,
					// so we don't need to care about the stop duration
					double stopDuration = routeStop.getDepartureOffset().isDefined() ?
						routeStop.getDepartureOffset().seconds() - routeStop.getArrivalOffset().seconds() : minStopTime;
					// ensure arrival at next stop early enough to allow for 30s stop duration -> time for passengers to board / alight
					// if link freespeed had been set such that the pt veh arrives exactly on time, but departure tiome is identical
					// with arrival time the pt vehicle would have been always delayed
					// Math.max to avoid negative values of travelTime
					double travelTime = Math.max(1, routeStop.getArrivalOffset().seconds() - lastDepartureOffset - 1.0 -
						(stopDuration >= minStopTime ? 0 : (minStopTime - stopDuration))) ;
					Link link = network.getLinks().get(routeStop.getStopFacility().getLinkId());
					increaseLinkFreespeedIfLower(link, link.getLength() / travelTime);
					lastDepartureOffset = routeStop.getDepartureOffset().seconds();
				}

				// create vehicles for Departures
				for (Departure departure: route.getDepartures().values()) {
					Vehicle veh = vehicleFactory.createVehicle(Id.create("pt_" + route.getId().toString() + "_" + Long.toString(routeVehId++), Vehicle.class), lineVehicleType);
					scenario.getTransitVehicles().addVehicle(veh);
					departure.setVehicleId(veh.getId());
				}

				// tag RE, RB, S- and U-Bahn stations for Drt stop filter attribute
				if (!stopFilter.isEmpty()) {
					for (TransitRouteStop routeStop: route.getStops()) {
						routeStop.getStopFacility().getAttributes().putAttribute("stopFilter", stopFilter);
					}
				}
			}
		}

		for (var type : scenario.getTransitVehicles().getVehicleTypes().values()) {
			var engineInformation = type.getEngineInformation();
			// ignore in emission calculations, as we don't have transit vehicles on the network
			VehicleUtils.setHbefaVehicleCategory(engineInformation, HbefaVehicleCategory.NON_HBEFA_VEHICLE.toString());
			VehicleUtils.setHbefaTechnology(engineInformation, "average");
			VehicleUtils.setHbefaSizeClass(engineInformation, "average");
			VehicleUtils.setHbefaEmissionsConcept(engineInformation, "average");
		}
	}

	private static void increaseLinkFreespeedIfLower(Link link, double newFreespeed) {
		if (link.getFreespeed() < newFreespeed) {
			link.setFreespeed(newFreespeed);
		}
	}

	private static Set<Long> readNodeIds(Path mappingFile) {

		Set<Long> result = new LongOpenHashSet();
		TabularFileParserConfig config = new TabularFileParserConfig();
		config.setDelimiterTags(new String[] {";"});
		config.setFileName(mappingFile.toString());
		new TabularFileParser().parse(config, new TabularFileHandler() {
			boolean header = true;
			@Override
			public void startRow(String[] row) {
				if(!header){
					if( !(row[1].isEmpty() || row[2].isEmpty()) ){
						result.add( Long.parseLong(row[1]));
						result.add( Long.parseLong(row[2]));
					}
				}
				header = false;
			}
		});

		return result;
	}



	private static class Input {

		@Parameter(required = true, names = "-publicSvn")
		private String publicSvn;

		@Parameter(required = true, names = "-sharedSvn")
		private String sharedSvn;
	}
}
