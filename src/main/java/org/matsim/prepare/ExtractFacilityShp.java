package org.matsim.prepare;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slimjars.dist.gnu.trove.iterator.TLongObjectIterator;
import de.topobyte.osm4j.core.dataset.InMemoryMapDataSet;
import de.topobyte.osm4j.core.dataset.MapDataSetLoader;
import de.topobyte.osm4j.core.model.iface.*;
import de.topobyte.osm4j.core.resolve.EntityNotFoundException;
import de.topobyte.osm4j.geometry.GeometryBuilder;
import de.topobyte.osm4j.pbf.seq.PbfIterator;
import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.geotools.data.DefaultTransaction;
import org.geotools.data.FileDataStoreFactorySpi;
import org.geotools.data.Transaction;
import org.geotools.data.shapefile.ShapefileDataStore;
import org.geotools.data.shapefile.ShapefileDataStoreFactory;
import org.geotools.data.simple.SimpleFeatureStore;
import org.geotools.feature.DefaultFeatureCollection;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.CRS;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.index.strtree.STRtree;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.CrsOptions;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.TransportModeNetworkFilter;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.facilities.*;
import org.matsim.run.RunOpenBerlinScenario;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.referencing.crs.CRSAuthorityFactory;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.TransformException;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.matsim.prepare.RunOpenBerlinCalibration.roundCoord;

@CommandLine.Command(
	name = "facility-shp",
	description = "Generate facility shape file from OSM data."
)
public class ExtractFacilityShp implements MATSimAppCommand {

	private static final Logger log = LogManager.getLogger(ExtractFacilityShp.class);
	private static final double POI_BUFFER = 6;

	/**
	 * Structures of this size are completely ignored.
	 */
	private static final double MAX_AREA = 50_000_000;

	/**
	 * Structures larger than this will not be assigned smaller scale types, but remain independently.
	 * Usually large areas such as parks, campus, etc.
	 */
	private static final double MAX_ASSIGN = 50_000;
	private final GeometryBuilder geometryBuilder = new GeometryBuilder();
	@CommandLine.Option(names = "--input", description = "Path to input .pbf file", required = true)
	private Path pbf;
	@CommandLine.Option(names = "--output", description = "Path to output shape file", required = true)
	private Path output;
	@CommandLine.Option(names = "--activity-mapping", description = "Path to activity napping json", required = true)
	private Path mappingPath;
	@CommandLine.Mixin
	private CrsOptions crs = new CrsOptions("EPSG:4326", RunOpenBerlinScenario.CRS);
	/**
	 * Maps types to feature index.
	 */
	private Object2IntMap<String> types;
	private ActivityMapping config;
	private List<Feature> pois;
	private List<Feature> landuse;
	private List<Feature> entities;
	private MathTransform transform;
	private InMemoryMapDataSet data;
	private int ignored;

	public static void main(String[] args) {
		new ExtractFacilityShp().execute(args);
	}

	@Override
	public Integer call() throws Exception {

		PbfIterator reader = new PbfIterator(Files.newInputStream(pbf), true);

		config = new ObjectMapper().readerFor(ActivityMapping.class).readValue(mappingPath.toFile());

		CRSAuthorityFactory cFactory = CRS.getAuthorityFactory(true);
		transform = CRS.findMathTransform(cFactory.createCoordinateReferenceSystem(crs.getInputCRS()), CRS.decode(crs.getTargetCRS()), true);

		log.info("Configured tags: {}", config.getTypes());

		types = new Object2IntLinkedOpenHashMap<>();

		config.types.values().stream()
			.flatMap(c -> c.values.values().stream())
			.flatMap(Collection::stream)
			.distinct()
			.sorted()
			.forEach(e -> types.put(e, types.size()));


		log.info("Configured activity types: {}", types.keySet());

		if (types.keySet().stream().anyMatch(t -> t.length() > 10)) {
			log.error("Activity names max length is 10, due to shp format limitation.");
			return 2;
		}


		// Collect all geometries first
		pois = new ArrayList<>();
		entities = new ArrayList<>();
		landuse = new ArrayList<>();

		data = MapDataSetLoader.read(reader, true, true, true);

		log.info("Finished loading pbf file.");

		TLongObjectIterator<OsmNode> it = data.getNodes().iterator();
		while (it.hasNext()) {
			it.advance();
			process(it.value());
		}

		log.info("Collected {} POIs", pois.size());

		TLongObjectIterator<OsmWay> it2 = data.getWays().iterator();
		while (it2.hasNext()) {
			it2.advance();
			process(it2.value());
		}

		TLongObjectIterator<OsmRelation> it3 = data.getRelations().iterator();
		while (it3.hasNext()) {
			it3.advance();
			process(it3.value());
		}

		log.info("Collected {} landuse shapes", landuse.size());
		log.info("Collected {} other entities", entities.size());

		if (ignored > 0)
			log.warn("Ignored {} invalid geometries", ignored);


		STRtree index = new STRtree();
		for (Feature entity : entities) {
			index.insert(entity.geometry.getBoundary().getEnvelopeInternal(), entity);
		}
		index.build();

		processIntersection(landuse, index);

		log.info("Remaining landuse shapes after assignment: {} ", landuse.size());

		processIntersection(pois, index);

		log.info("Remaining POI after assignment: {}", pois.size());

		FileDataStoreFactorySpi factory = new ShapefileDataStoreFactory();

		ShapefileDataStore ds = (ShapefileDataStore) factory.createNewDataStore(Map.of("url", output.toFile().toURI().toURL()));

		SimpleFeatureTypeBuilder typeBuilder = new SimpleFeatureTypeBuilder();
		typeBuilder.setName("schema");
		typeBuilder.setCRS(CRS.decode(crs.getTargetCRS()));
		typeBuilder.add("the_geom", MultiPolygon.class);
		for (String t : types.keySet()) {
			typeBuilder.add(t, Boolean.class);
		}

		SimpleFeatureType featureType = typeBuilder.buildFeatureType();
		ds.createSchema(featureType);

		SimpleFeatureStore source = (SimpleFeatureStore) ds.getFeatureSource();
		SimpleFeatureBuilder featureBuilder = new SimpleFeatureBuilder(featureType);
		DefaultFeatureCollection collection = new DefaultFeatureCollection(null, featureType);

		Transaction transaction = new DefaultTransaction("create");
		source.setTransaction(transaction);

		addFeatures(entities, featureBuilder, collection);
		addFeatures(landuse, featureBuilder, collection);
		addFeatures(pois, featureBuilder, collection);

		source.addFeatures(collection);
		transaction.commit();
		transaction.close();

		ds.dispose();

		return 0;
	}

	/**
	 * Tags buildings within that intersections with geometries from list. Used geometries are removed from the list.
	 */
	private void processIntersection(List<Feature> list, STRtree index) {

		Iterator<Feature> it = list.iterator();
		while (it.hasNext()) {
			Feature ft = it.next();

			List<Feature> query = index.query(ft.geometry.getBoundary().getEnvelopeInternal());

			boolean used = false;
			for (Feature other : query) {
				// Assign other features to the buildings
				try {
					if (ft.geometry.intersects(other.geometry) && other.geometry.getArea() < MAX_ASSIGN) {
						other.assign(ft);
						used = true;
					}
				} catch (TopologyException e) {
					// some geometries are not well defined
					if (ft.geometry.getBoundary().intersects(other.geometry.getBoundary()) && other.geometry.getArea() < MAX_ASSIGN) {
						other.assign(ft);
						used = true;
					}
				}
			}

			if (used)
				it.remove();
		}
	}

	private void addFeatures(List<Feature> fts, SimpleFeatureBuilder featureBuilder, DefaultFeatureCollection collection) {
		for (Feature ft : fts) {
			// Relations are ignored at this point
			if (!ft.bits.isEmpty())
				collection.add(ft.createFeature(featureBuilder));
		}
	}

	/**
	 * Stores entities and geometries as necessary.
	 */
	private void process(OsmEntity entity) {
		boolean filtered = true;
		int n = entity.getNumberOfTags();
		for (int i = 0; i < n; i++) {
			OsmTag tag = entity.getTag(i);

			// Buildings are always kept
			if (tag.getKey().equals("building")) {
				filtered = false;
				break;
			}

			MappingConfig c = config.types.get(tag.getKey());
			if (c != null) {
				if (c.values.containsKey("*") || c.values.containsKey(tag.getValue())) {
					filtered = false;
					break;
				}
			}
		}

		if (filtered)
			return;

		if (entity instanceof OsmNode node) {

			Point p = geometryBuilder.build(node);
			MultiPolygon geometry;
			try {
				Polygon polygon = (Polygon) JTS.transform(p, transform).buffer(POI_BUFFER);
				geometry = geometryBuilder.getGeometryFactory().createMultiPolygon(new Polygon[]{polygon});
			} catch (TransformException e) {
				ignored++;
				return;
			}

			pois.add(new Feature(entity, types.size(), geometry));
		} else {
			boolean landuse = false;
			for (int i = 0; i < n; i++) {
				if (entity.getTag(i).getKey().equals("landuse")) {
					landuse = true;
					break;
				}
			}

			MultiPolygon geometry;
			try {
				geometry = createPolygon(entity);
				if (geometry == null) {
					ignored++;
					return;
				}
				geometry = (MultiPolygon) JTS.transform(geometry, transform);
			} catch (TransformException e) {
				// Will be ignored
				geometry = null;
			}

			if (geometry == null) {
				ignored++;
				return;
			}

			Feature ft = new Feature(entity, types.size(), geometry);
			if (landuse) {
				this.landuse.add(ft);
			} else {

				// some non landuse shapes might be too large
				if (ft.geometry.getArea() < MAX_AREA)
					entities.add(ft);
			}

		}
	}


	private MultiPolygon createPolygon(OsmEntity entity) {
		Geometry geom = null;
		try {
			if (entity instanceof OsmWay) {
				geom = geometryBuilder.build((OsmWay) entity, data);
			} else if (entity instanceof OsmRelation) {
				geom = geometryBuilder.build((OsmRelation) entity, data);
			}
		} catch (EntityNotFoundException e) {
			return null;
		}

		if (geom == null)
			throw new IllegalStateException("Unrecognized type.");

		if (geom instanceof LinearRing lr) {
			Polygon polygon = geometryBuilder.getGeometryFactory().createPolygon(lr);
			return geometryBuilder.getGeometryFactory().createMultiPolygon(new Polygon[]{polygon});
		} else if (geom instanceof MultiPolygon p) {
			return p;
		}

		return null;
	}

	private static final class ActivityMapping {
		private final Map<String, MappingConfig> types = new HashMap<>();

		public Set<String> getTypes() {
			return types.keySet();
		}

		@JsonAnyGetter
		public MappingConfig getTag(String type) {
			return types.get(type);
		}

		@JsonAnySetter
		private void setTag(String type, MappingConfig config) {
			types.put(type, config);
		}

	}

	/**
	 * Helper class to define data structure for mapping.
	 */
	public static final class MappingConfig {

		private final Map<String, Set<String>> values = new HashMap<>();

		@JsonAnyGetter
		public Set<String> getActivities(String value) {
			return values.get(value);
		}

		@JsonAnySetter
		private void setActivities(String value, Set<String> activities) {
			values.put(value, activities);
		}

	}

	@CommandLine.Command(
		name = "facilities",
		description = "Creates MATSim facilities from shape-file and network"
	)
	public static class CreateMATSimFacilities implements MATSimAppCommand {

		/**
		 * Filter link types that don't have a facility associated.
		 */
		public static final Set<String> IGNORED_LINK_TYPES = Set.of("motorway", "trunk",
			"motorway_link", "trunk_link", "secondary_link", "primary_link");
		private static final Logger log = LogManager.getLogger(CreateMATSimFacilities.class);
		@CommandLine.Option(names = "--network", required = true, description = "Path to car network")
		private Path network;

		@CommandLine.Option(names = "--output", required = true, description = "Path to output facility file")
		private Path output;

		@CommandLine.Mixin
		private ShpOptions shp;

		public static void main(String[] args) {
			new CreateMATSimFacilities().execute(args);
		}

		@Override
		public Integer call() throws Exception {

			if (shp.getShapeFile() == null) {
				log.error("Shp file with facilities is required.");
				return 2;
			}

			Network completeNetwork = NetworkUtils.readNetwork(this.network.toString());
			TransportModeNetworkFilter filter = new TransportModeNetworkFilter(completeNetwork);
			Network carOnlyNetwork = NetworkUtils.createNetwork();
			filter.filter(carOnlyNetwork, Set.of(TransportMode.car));

			List<SimpleFeature> fts = shp.readFeatures();

			Map<Id<Link>, Holder> data = new ConcurrentHashMap<>();

			fts.parallelStream().forEach(ft -> processFeature(ft, carOnlyNetwork, data));

			ActivityFacilities facilities = FacilitiesUtils.createActivityFacilities();

			ActivityFacilitiesFactory f = facilities.getFactory();

			for (Map.Entry<Id<Link>, Holder> e : data.entrySet()) {

				Holder h = e.getValue();

				Id<ActivityFacility> id = Id.create(String.join("_", h.ids), ActivityFacility.class);

				// Create mean coordinate
				OptionalDouble x = h.coords.stream().mapToDouble(Coord::getX).average();
				OptionalDouble y = h.coords.stream().mapToDouble(Coord::getY).average();

				if (x.isEmpty() || y.isEmpty()) {
					log.warn("Empty coordinate (Should never happen)");
					continue;
				}

				ActivityFacility facility = f.createActivityFacility(id, roundCoord(new Coord(x.getAsDouble(), y.getAsDouble())));
				for (String act : h.activities) {
					facility.addActivityOption(f.createActivityOption(act));
				}

				facilities.addActivityFacility(facility);
			}

			log.info("Created {} facilities, writing to {}", facilities.getFacilities().size(), output);

			FacilitiesWriter writer = new FacilitiesWriter(facilities);
			writer.write(output.toString());

			return 0;
		}

		/**
		 * Sample points and choose link with the nearest points. Aggregate everything so there is at most one facility per link.
		 */
		private void processFeature(SimpleFeature ft, Network network, Map<Id<Link>, Holder> data) {

			// Actual id is the last part
			String[] id = ft.getID().split("\\.");

			// Pairs of coords and corresponding links
			List<Coord> coords = samplePoints((MultiPolygon) ft.getDefaultGeometry(), 23);
			List<Id<Link>> links = coords.stream().map(coord -> NetworkUtils.getNearestLinkExactly(network, coord).getId()).toList();

			Map<Id<Link>, Long> map = links.stream()
				.filter(l -> !IGNORED_LINK_TYPES.contains(NetworkUtils.getType(network.getLinks().get(l))))
				.collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

			// Everything could be filtered and map empty
			if (map.isEmpty())
				return;

			List<Map.Entry<Id<Link>, Long>> counts = map.entrySet().stream().sorted(Map.Entry.comparingByValue())
				.toList();

			// The "main" link of the facility
			Id<Link> link = counts.get(counts.size() - 1).getKey();

			Holder holder = data.computeIfAbsent(link, k -> new Holder(ConcurrentHashMap.newKeySet(), ConcurrentHashMap.newKeySet(), Collections.synchronizedList(new ArrayList<>())));

			holder.ids.add(id[id.length - 1]);
			holder.activities.addAll(activities(ft));

			// Search for the original drawn coordinate of the associated link
			for (int i = 0; i < links.size(); i++) {
				if (links.get(i).equals(link)) {
					holder.coords.add(coords.get(i));
					break;
				}
			}
		}

		/**
		 * Sample coordinates within polygon.
		 */
		private List<Coord> samplePoints(MultiPolygon geometry, int n) {

			SplittableRandom rnd = new SplittableRandom();

			List<Coord> result = new ArrayList<>();
			Envelope bbox = geometry.getEnvelopeInternal();
			int max = n * 10;
			for (int i = 0; i < max && result.size() < n; i++) {

				Coord coord = roundCoord(new Coord(
					bbox.getMinX() + (bbox.getMaxX() - bbox.getMinX()) * rnd.nextDouble(),
					bbox.getMinY() + (bbox.getMaxY() - bbox.getMinY()) * rnd.nextDouble()
				));

				try {
					if (geometry.contains(MGC.coord2Point(coord))) {
						result.add(coord);
					}
				} catch (TopologyException e) {
					if (geometry.getBoundary().contains(MGC.coord2Point(coord))) {
						result.add(coord);
					}
				}

			}

			if (result.isEmpty())
				result.add(MGC.point2Coord(geometry.getCentroid()));

			return result;
		}

		private Set<String> activities(SimpleFeature ft) {
			Set<String> act = new HashSet<>();

			if (Boolean.TRUE == ft.getAttribute("work")) {
				act.add("work");
				act.add("work_business");
			}
			if (Boolean.TRUE == ft.getAttribute("shop")) {
				act.add("shop_other");
			}
			if (Boolean.TRUE == ft.getAttribute("shop_daily")) {
				act.add("shop_other");
				act.add("shop_daily");
			}
			if (Boolean.TRUE == ft.getAttribute("leisure"))
				act.add("leisure");
			if (Boolean.TRUE == ft.getAttribute("dining"))
				act.add("dining");
			if (Boolean.TRUE == ft.getAttribute("edu_higher"))
				act.add("edu_higher");
			if (Boolean.TRUE == ft.getAttribute("edu_prim")) {
				act.add("edu_primary");
				act.add("edu_secondary");
			}
			if (Boolean.TRUE == ft.getAttribute("edu_kiga"))
				act.add("edu_kiga");
			if (Boolean.TRUE == ft.getAttribute("edu_other"))
				act.add("edu_other");
			if (Boolean.TRUE == ft.getAttribute("p_business") || Boolean.TRUE == ft.getAttribute("medical") || Boolean.TRUE == ft.getAttribute("religious")) {
				act.add("personal_business");
				act.add("work_business");
			}

			return act;
		}

		/**
		 * Temporary data holder for facilities.
		 */
		private record Holder(Set<String> ids, Set<String> activities, List<Coord> coords) {

		}

	}

	/**
	 * Features for one facility, stored as bit set.
	 */
	private final class Feature {
		private final OsmEntity entity;

		private final BitSet bits;

		private final MultiPolygon geometry;

		Feature(OsmEntity entity, int n, MultiPolygon geometry) {
			this.entity = entity;
			this.bits = new BitSet(n);
			this.bits.clear();
			this.geometry = geometry;

			parse(entity);
		}

		/**
		 * Parse tags into features. Can also be from different entity.
		 */
		private void parse(OsmEntity entity) {
			for (int i = 0; i < entity.getNumberOfTags(); i++) {

				OsmTag tag = entity.getTag(i);

				MappingConfig conf = config.getTag(tag.getKey());
				if (conf == null)
					continue;

				if (conf.values.containsKey("*")) {
					set(conf.values.get("*"));
				}

				if (conf.values.containsKey(tag.getValue())) {
					set(conf.values.get(tag.getValue()));
				}
			}
		}

		private void set(Set<String> acts) {
			for (String act : acts) {
				bits.set(types.getInt(act), true);
			}
		}

		void assign(Feature other) {
			for (int i = 0; i < types.size(); i++) {
				if (other.bits.get(i))
					this.bits.set(i);
			}
		}

		public SimpleFeature createFeature(SimpleFeatureBuilder builder) {

			builder.add(geometry);
			for (int i = 0; i < types.size(); i++) {
				builder.add(bits.get(i));
			}
			return builder.buildFeature(null);
		}
	}
}
