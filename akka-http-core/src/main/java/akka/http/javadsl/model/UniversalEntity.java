/*
 * Copyright (C) 2009-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.model;

/** Marker-interface for entity types that can be used in any context */
public interface UniversalEntity extends RequestEntity, ResponseEntity, BodyPartEntity {}
