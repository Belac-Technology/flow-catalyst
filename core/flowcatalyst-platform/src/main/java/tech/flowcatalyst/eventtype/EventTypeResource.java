package tech.flowcatalyst.eventtype;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import tech.flowcatalyst.eventtype.operations.*;

import java.util.List;

@Path("/api/event-types")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Event Types", description = "Manage event type definitions")
public class EventTypeResource {

    @Inject
    EventTypeService eventTypeService;

    @GET
    @Operation(summary = "List all event types", description = "Returns all event types with optional filtering. Supports multi-value filtering with comma-separated values.")
    @APIResponses({
        @APIResponse(
            responseCode = "200",
            description = "List of event types",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = EventTypeListResponse.class))
        )
    })
    public Response listEventTypes(
        @QueryParam("status") EventTypeStatus status,
        @QueryParam("application") List<String> applications,
        @QueryParam("subdomain") List<String> subdomains,
        @QueryParam("aggregate") List<String> aggregates
    ) {
        // Filter empty strings from the lists
        List<String> filteredApps = filterEmpty(applications);
        List<String> filteredSubs = filterEmpty(subdomains);
        List<String> filteredAggs = filterEmpty(aggregates);

        List<EventType> eventTypes = eventTypeService.findWithFilters(
            filteredApps.isEmpty() ? null : filteredApps,
            filteredSubs.isEmpty() ? null : filteredSubs,
            filteredAggs.isEmpty() ? null : filteredAggs,
            status
        );

        List<EventTypeResponse> responses = eventTypes.stream()
            .map(EventTypeResponse::from)
            .toList();

        return Response.ok(new EventTypeListResponse(responses)).build();
    }

    private List<String> filterEmpty(List<String> list) {
        if (list == null) return List.of();
        return list.stream()
            .filter(s -> s != null && !s.isBlank())
            .toList();
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "Get event type by ID")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Event type found"),
        @APIResponse(responseCode = "404", description = "Event type not found")
    })
    public Response getEventType(@PathParam("id") Long id) {
        return eventTypeService.findById(id)
            .map(et -> Response.ok(EventTypeResponse.from(et)).build())
            .orElse(Response.status(404)
                .entity(new ErrorResponse("Event type not found: " + id))
                .build());
    }

    @GET
    @Path("/filters/applications")
    @Operation(summary = "Get distinct application names for filtering")
    public Response getApplications() {
        List<String> applications = eventTypeService.getDistinctApplications();
        return Response.ok(new FilterOptionsResponse(applications)).build();
    }

    @GET
    @Path("/filters/subdomains")
    @Operation(summary = "Get distinct subdomains, optionally filtered by applications")
    public Response getSubdomains(@QueryParam("application") List<String> applications) {
        List<String> filteredApps = filterEmpty(applications);
        List<String> subdomains = eventTypeService.getDistinctSubdomains(filteredApps);
        return Response.ok(new FilterOptionsResponse(subdomains)).build();
    }

    @GET
    @Path("/filters/aggregates")
    @Operation(summary = "Get distinct aggregates, optionally filtered by applications and subdomains")
    public Response getAggregates(
        @QueryParam("application") List<String> applications,
        @QueryParam("subdomain") List<String> subdomains
    ) {
        List<String> filteredApps = filterEmpty(applications);
        List<String> filteredSubs = filterEmpty(subdomains);
        List<String> aggregates = eventTypeService.getDistinctAggregates(
            filteredApps.isEmpty() ? null : filteredApps,
            filteredSubs.isEmpty() ? null : filteredSubs
        );
        return Response.ok(new FilterOptionsResponse(aggregates)).build();
    }

    @POST
    @Operation(summary = "Create a new event type")
    @APIResponses({
        @APIResponse(responseCode = "201", description = "Event type created"),
        @APIResponse(responseCode = "400", description = "Invalid request")
    })
    public Response createEventType(CreateEventTypeRequest request) {
        EventType eventType = eventTypeService.execute(new CreateEventType(
            request.code(),
            request.name(),
            request.description()
        ));
        return Response.status(201).entity(EventTypeResponse.from(eventType)).build();
    }

    @PATCH
    @Path("/{id}")
    @Operation(summary = "Update an event type's name or description")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Event type updated"),
        @APIResponse(responseCode = "400", description = "Invalid request"),
        @APIResponse(responseCode = "404", description = "Event type not found")
    })
    public Response updateEventType(@PathParam("id") Long id, UpdateEventTypeRequest request) {
        EventType eventType = eventTypeService.execute(new UpdateEventType(
            id,
            request.name(),
            request.description()
        ));
        return Response.ok(EventTypeResponse.from(eventType)).build();
    }

    @POST
    @Path("/{id}/schemas")
    @Operation(summary = "Add a new schema version to an event type")
    @APIResponses({
        @APIResponse(responseCode = "201", description = "Schema added"),
        @APIResponse(responseCode = "400", description = "Invalid request"),
        @APIResponse(responseCode = "404", description = "Event type not found")
    })
    public Response addSchema(@PathParam("id") Long id, AddSchemaRequest request) {
        EventType eventType = eventTypeService.execute(new AddSchema(
            id,
            request.version(),
            request.mimeType(),
            request.schema(),
            request.schemaType()
        ));
        return Response.status(201).entity(EventTypeResponse.from(eventType)).build();
    }

    @POST
    @Path("/{id}/schemas/{version}/finalise")
    @Operation(summary = "Finalise a schema version (FINALISING → CURRENT)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Schema finalised"),
        @APIResponse(responseCode = "400", description = "Invalid request"),
        @APIResponse(responseCode = "404", description = "Event type or version not found")
    })
    public Response finaliseSchema(@PathParam("id") Long id, @PathParam("version") String version) {
        EventType eventType = eventTypeService.execute(new FinaliseSchema(id, version));
        return Response.ok(EventTypeResponse.from(eventType)).build();
    }

    @POST
    @Path("/{id}/schemas/{version}/deprecate")
    @Operation(summary = "Deprecate a schema version (CURRENT → DEPRECATED)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Schema deprecated"),
        @APIResponse(responseCode = "400", description = "Invalid request"),
        @APIResponse(responseCode = "404", description = "Event type or version not found")
    })
    public Response deprecateSchema(@PathParam("id") Long id, @PathParam("version") String version) {
        EventType eventType = eventTypeService.execute(new DeprecateSchema(id, version));
        return Response.ok(EventTypeResponse.from(eventType)).build();
    }

    @POST
    @Path("/{id}/archive")
    @Operation(summary = "Archive an event type (CURRENT → ARCHIVE)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Event type archived"),
        @APIResponse(responseCode = "400", description = "Invalid request - all schemas must be deprecated first"),
        @APIResponse(responseCode = "404", description = "Event type not found")
    })
    public Response archiveEventType(@PathParam("id") Long id) {
        EventType eventType = eventTypeService.execute(new ArchiveEventType(id));
        return Response.ok(EventTypeResponse.from(eventType)).build();
    }

    @DELETE
    @Path("/{id}")
    @Operation(summary = "Delete an event type")
    @APIResponses({
        @APIResponse(responseCode = "204", description = "Event type deleted"),
        @APIResponse(responseCode = "400", description = "Invalid request - must be archived or have no finalized schemas"),
        @APIResponse(responseCode = "404", description = "Event type not found")
    })
    public Response deleteEventType(@PathParam("id") Long id) {
        eventTypeService.execute(new DeleteEventType(id));
        return Response.noContent().build();
    }

    // ========================================================================
    // DTOs
    // ========================================================================

    public record EventTypeListResponse(List<EventTypeResponse> items) {}

    public record EventTypeResponse(
        Long id,
        String code,
        String name,
        String description,
        EventTypeStatus status,
        String application,
        String subdomain,
        String aggregate,
        String event,
        List<SpecVersionResponse> specVersions,
        String createdAt,
        String updatedAt
    ) {
        public static EventTypeResponse from(EventType et) {
            String[] parts = et.code.split(":");
            return new EventTypeResponse(
                et.id,
                et.code,
                et.name,
                et.description,
                et.status,
                parts.length > 0 ? parts[0] : null,
                parts.length > 1 ? parts[1] : null,
                parts.length > 2 ? parts[2] : null,
                parts.length > 3 ? parts[3] : null,
                et.specVersions != null
                    ? et.specVersions.stream().map(SpecVersionResponse::from).toList()
                    : List.of(),
                et.createdAt != null ? et.createdAt.toString() : null,
                et.updatedAt != null ? et.updatedAt.toString() : null
            );
        }
    }

    public record SpecVersionResponse(
        String version,
        String mimeType,
        String schemaType,
        String status
    ) {
        public static SpecVersionResponse from(SpecVersion sv) {
            return new SpecVersionResponse(
                sv.version(),
                sv.mimeType(),
                sv.schemaType() != null ? sv.schemaType().name() : null,
                sv.status() != null ? sv.status().name() : null
            );
        }
    }

    public record CreateEventTypeRequest(
        String code,
        String name,
        String description
    ) {}

    public record UpdateEventTypeRequest(
        String name,
        String description
    ) {}

    public record AddSchemaRequest(
        String version,
        String mimeType,
        String schema,
        SchemaType schemaType
    ) {}

    public record FilterOptionsResponse(List<String> options) {}

    public record ErrorResponse(String error) {}
}
