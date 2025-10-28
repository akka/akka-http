/*
 * Copyright (C) 2009-2025 Lightbend Inc. <https://akka.io>
 */

package akka.http.javadsl.model;

/** Marker-interface for entity types that can be used in any context */
public interface UniversalEntity extends RequestEntity, ResponseEntity, BodyPartEntity {}
