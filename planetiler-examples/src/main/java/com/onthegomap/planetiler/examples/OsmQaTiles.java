package com.onthegomap.planetiler.examples;

import com.onthegomap.planetiler.FeatureCollector;
import com.onthegomap.planetiler.Planetiler;
import com.onthegomap.planetiler.Profile;
import com.onthegomap.planetiler.VectorTile;
import com.onthegomap.planetiler.config.Arguments;
import com.onthegomap.planetiler.geo.GeometryException;
import com.onthegomap.planetiler.geo.GeometryType;
import com.onthegomap.planetiler.reader.SourceFeature;
import com.onthegomap.planetiler.reader.osm.OsmElement;
import com.onthegomap.planetiler.reader.osm.OsmSourceFeature;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.locationtech.jts.geom.CoordinateXY;
import org.locationtech.jts.geom.Geometry;

/**
 * Generates tiles with a raw copy of OSM data in a single "osm" layer at one zoom level, similar to
 * <a href="http://osmlab.github.io/osm-qa-tiles/">OSM QA Tiles</a>.
 * <p>
 * Nodes are mapped to points and ways are mapped to polygons or linestrings, and multipolygon relations are mapped to
 * polygons. Each output feature contains all key/value tags from the input feature, plus these extra attributes:
 * <ul>
 * <li>{@code @type}: node, way, or relation</li>
 * <li>{@code @id}: OSM element ID</li>
 * <li>{@code @changeset}: Changeset that last modified the element</li>
 * <li>{@code @timestamp}: Timestamp at which the element was last modified</li>
 * <li>{@code @version}: Version number of the OSM element</li>
 * <li>{@code @uid}: User ID that last modified the element</li>
 * <li>{@code @user}: User name that last modified the element</li>
 * </ul>
 * <p>
 * To run this example:
 * <ol>
 * <li>build the examples: {@code mvn clean package}</li>
 * <li>then run this example:
 * {@code java -cp target/*-fatjar.jar com.onthegomap.planetiler.examples.OsmQaTiles --area=monaco --download}</li>
 * <li>then run the demo tileserver: {@code tileserver-gl-light data/output.mbtiles}</li>
 * <li>and view the output at <a href="http://localhost:8080">localhost:8080</a></li>
 * </ol>
 */
public class OsmQaTiles implements Profile {

  private final int maxzoom;

  public OsmQaTiles(int maxzoom) {
    this.maxzoom = maxzoom;
  }

  public static void main(String[] args) throws Exception {
    run(Arguments.fromArgsOrConfigFile(args));
  }

  static void run(Arguments inArgs) throws Exception {
    int zoom = inArgs.getInteger("zoom", "zoom level to generate tiles at", 12);
    var args = inArgs.orElse(Arguments.of(
      "minzoom", zoom,
      "maxzoom", zoom,
      "tile_warning_size_mb", 100
    ));
    String area = args.getString("area", "geofabrik area to download", "monaco");
    Planetiler.create(args)
      .setProfile(new OsmQaTiles(zoom))
      .addOsmSource("osm",
        Path.of("data", "sources", area + ".osm.pbf"),
        "planet".equalsIgnoreCase(area) ? "aws:latest" : ("geofabrik:" + area)
      )
      .overwriteOutput(Path.of("data", "qa.mbtiles"))
      .run();
  }

  @Override
  public void processFeature(SourceFeature sourceFeature, FeatureCollector features) {
    if (sourceFeature.tags().isEmpty() || !(sourceFeature instanceof OsmSourceFeature osmFeature)) {
      return;
    }

    var feature = features.anyGeometry("osm");

    if (feature == null) {
      return;
    }

    feature
      .setMinPixelSize(0)
      .setPixelTolerance(0)
      // Try to avoid showing tile boundaries with large polygons
      .setBufferPixels(1);

    for (var entry : sourceFeature.tags().entrySet()) {
      feature.setAttr(entry.getKey(), entry.getValue());
    }

    var element = osmFeature.originalElement();
    feature
      .setAttr("@id", sourceFeature.id())
      .setAttr("@type", switch (element) {
        case OsmElement.Node ignored -> "node";
        case OsmElement.Way ignored -> "way";
        case OsmElement.Relation ignored -> "relation";
        default -> null;
      });

    var info = element.info();
    if (info == null) {
      return;
    }

    feature
      .setAttr("@version", info.version() == 0 ? null : info.version())
      .setAttr("@timestamp", info.timestamp() == 0L ? null : info.timestamp())
      .setAttr("@changeset", info.changeset() == 0L ? null : info.changeset())
      .setAttr("@uid", info.userId() == 0 ? null : info.userId())
      .setAttr("@user", info.user() == null || info.user().isBlank() ? null : info.user());
  }

  @Override
  public List<VectorTile.Feature> postProcessLayerFeatures(String layer, int zoom,
    List<VectorTile.Feature> items) throws GeometryException {
    // If we're at the max zoom, then return null to indicate that we don't need to do any further processing
    if (zoom == this.maxzoom) {
      return null;
    }

    // Otherwise, if the area of the feature is less than 1 pixel at the current zoom level, then convert it to a point
    List<VectorTile.Feature> newItems = new ArrayList<>(items.size());
    for (var item : items) {
      var geom = item.geometry().decode();

      // If the geometry is empty, then skip it
      if (geom.isEmpty()) {
        throw new GeometryException.Verbose("empty_geometry", "Empty geometry")
          .addGeometryDetails("Empty geometry", geom);
      }

      switch (geom.getGeometryType()) {
        case Geometry.TYPENAME_POINT, Geometry.TYPENAME_MULTIPOINT -> {
          // Add points directly
          newItems.add(item);
        }
        case Geometry.TYPENAME_LINESTRING, Geometry.TYPENAME_MULTILINESTRING -> {
          // If the length of the line is less than 1 pixel, convert it to a point
          if (geom.getLength() < 2.0) {
            newItems.add(item.copyWithNewGeometry(geom.getCentroid()));
          } else {
            newItems.add(item);
          }
        }
        case Geometry.TYPENAME_POLYGON, Geometry.TYPENAME_MULTIPOLYGON -> {
          // If the area of the polygon is less than 1 pixel, convert it to a point
          if (geom.getArea() < 2.0) {
            newItems.add(item.copyWithNewGeometry(geom.getCentroid()));
          } else {
            newItems.add(item);
          }
        }
        case Geometry.TYPENAME_GEOMETRYCOLLECTION -> {
          // If the area of the geometry collection is less than 1 pixel, convert it to a point
          if (geom.getArea() < 2.0) {
            newItems.add(item.copyWithNewGeometry(geom.getCentroid()));
          } else {
            newItems.add(item);
          }
        }
      }
    }

    // Only keep two points at each pixel
    Map<CoordinateXY, Integer> seenPoints = new HashMap<>();
    newItems = newItems.stream().filter(item -> {
      if (!GeometryType.POINT.equals(item.geometry().geomType())) {
        return true;
      }

      var pt = item.geometry().firstCoordinate();

      var pointCount = seenPoints.getOrDefault(pt, 0);
      if (pointCount > 2) {
        // If we have more than 2 points at this pixel, then remove the point
        return false;
      }

      seenPoints.put(pt, pointCount + 1);
      return true;
    }).toList();

    return newItems;
  }

  @Override
  public String name() {
    return "osm qa";
  }

  @Override
  public String attribution() {
    return """
      <a href="https://www.openstreetmap.org/copyright" target="_blank">&copy; OpenStreetMap contributors</a>
      """.trim();
  }
}
