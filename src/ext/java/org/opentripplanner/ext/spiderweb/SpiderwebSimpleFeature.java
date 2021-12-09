package org.opentripplanner.ext.spiderweb;


import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.geotools.data.DataUtilities;
import org.geotools.feature.SchemaException;
import org.locationtech.jts.geom.Geometry;
import org.opengis.feature.GeometryAttribute;
import org.opengis.feature.IllegalAttributeException;
import org.opengis.feature.Property;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.feature.type.AttributeDescriptor;
import org.opengis.feature.type.Name;
import org.opengis.filter.identity.FeatureId;
import org.opengis.geometry.BoundingBox;

abstract public class SpiderwebSimpleFeature implements SimpleFeature {

    static final SimpleFeatureType type;

    static {
        SimpleFeatureType featureType;
        try {
            featureType = DataUtilities.createType("feature", "geom:Geometry,mode:String,color:String,time:String,name:String,parent:Integer,trip:String,route:String,departureTime:String,staySeated:Boolean");
        }
        catch (SchemaException e) {
            featureType = null;
            e.printStackTrace();
        }
        type = featureType;
    }

    final Geometry geometry;
    final String id;

    public SpiderwebSimpleFeature(String id, Geometry geometry) {
        this.id = id;
        this.geometry = geometry;
    }

    @Override
    public String getID() {
        return id;
    }

    @Override
    public AttributeDescriptor getDescriptor() {
        return null;
    }

    @Override
    public Name getName() {
        return null;
    }

    @Override
    public boolean isNillable() {
        return false;
    }

    @Override
    public Map<Object, Object> getUserData() {
        return null;
    }

    @Override
    public SimpleFeatureType getType() {
        return type;
    }

    @Override
    public void setValue(Collection<Property> collection) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Collection<? extends Property> getValue() {
        return null;
    }

    @Override
    public void setValue(Object o) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Collection<Property> getProperties(Name name) {
        return null;
    }

    @Override
    public Property getProperty(Name name) {
        return null;
    }

    @Override
    public Collection<Property> getProperties(String s) {
        return null;
    }

    @Override
    public Collection<Property> getProperties() {
        return null;
    }

    @Override
    public Property getProperty(String s) {
        return null;
    }

    @Override
    public void validate() throws IllegalAttributeException {

    }

    @Override
    public FeatureId getIdentifier() {
        return null;
    }

    @Override
    public BoundingBox getBounds() {
        return null;
    }

    @Override
    public GeometryAttribute getDefaultGeometryProperty() {
        return null;
    }

    @Override
    public void setDefaultGeometryProperty(GeometryAttribute geometryAttribute) {
        throw new UnsupportedOperationException();
    }

    @Override
    public SimpleFeatureType getFeatureType() {
        return type;
    }

    @Override
    public List<Object> getAttributes() {
        return null;
    }

    @Override
    public void setAttributes(List<Object> list) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setAttributes(Object[] objects) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setAttribute(String s, Object o) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object getAttribute(Name name) {
        return getAttribute(name.getLocalPart());
    }

    @Override
    public void setAttribute(Name name, Object o) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object getAttribute(int i) throws IndexOutOfBoundsException {
        return null;
    }

    @Override
    public void setAttribute(int i, Object o) throws IndexOutOfBoundsException {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getAttributeCount() {
        return 0;
    }

    @Override
    public Object getDefaultGeometry() {
        return geometry;
    }

    @Override
    public void setDefaultGeometry(Object o) {
        throw new UnsupportedOperationException();
    }
}
