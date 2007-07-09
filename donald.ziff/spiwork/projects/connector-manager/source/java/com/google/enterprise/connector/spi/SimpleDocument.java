package com.google.enterprise.connector.spi;

import java.util.Iterator;
import java.util.Map;

public class SimpleDocument implements Document {
  
  public static Value getSingleValueProperty(Document document,
      String propertyName) throws RepositoryException {
    Value v = null; 
    if (document.findProperty(propertyName) && document.nextValue()) {
      v = document.getValue();
    }
    return v;
  }

  private Map properties;
  private Iterator iterator;
  private Property property;
  private String checkpoint;
   
  public SimpleDocument(Map properties) {
    this.properties = properties;
    this.iterator = null;
    this.property = null;
    this.checkpoint = null;
  }

  private void setCheckpoint() throws RepositoryException {
    if (checkpoint != null) {
      return;
    }
    Value v = getSingleValueProperty(this, SpiConstants.PROPNAME_LASTMODIFIED);
    checkpoint = v.toString();
  }
  
  public String checkpoint() throws RepositoryException {
    setCheckpoint();
    return checkpoint;
  }

  public boolean findProperty(String name) {
    if (iterator != null) {
      throw new IllegalStateException();
    }
    property = (Property) properties.get(name);
    return (property != null);
  }

  public boolean nextProperty() {
    if (iterator == null) {
      iterator = properties.values().iterator();
      try {
        setCheckpoint();
      } catch (RepositoryException e) {
        throw new IllegalStateException();
      }
    }
    boolean hasNext = iterator.hasNext();
    if (hasNext) {
      property = (Property) iterator.next();
    } else {
      property = null;
    }
    return hasNext;
  }

  public String getPropertyName() throws RepositoryException {
    if (property == null) {
      throw new IllegalStateException();
    }
    return property.getPropertyName();
  }

  public Value getValue() throws RepositoryException {
    if (property == null) {
      throw new IllegalStateException();
    }
    return property.getValue();
  }

  public boolean nextValue() {
    if (property == null) {
      throw new IllegalStateException();
    }
    return property.nextValue();
  }

}