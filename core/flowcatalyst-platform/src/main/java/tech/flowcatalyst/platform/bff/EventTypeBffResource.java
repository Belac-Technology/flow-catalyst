package tech.flowcatalyst.platform.bff;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import tech.flowcatalyst.platform.eventtype.*;
import tech.flowcatalyst.platform.eventtype.operations.*;

import java.util.List;

/**
 * BFF (Backend For Frontend) endpoints for Event Types.
 * Returns IDs as strings to preserve precision for JavaScript clients.
 */
@Path("/bff/event-types")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "BFF - Event Types", description = "Web-optimized event type endpoints with string IDs")
public class EventTypeBffResource {

    @Inject
    EventTypeService eventTypeService;

    @GET
    @Operation(summary = "List all event types (BFF)")
    public Response listEventTypes(
        @QueryParam("status") EventTypeStatus status,
        @QueryParam("application") List<String> applications,
        @QueryParam("subdomain") List<String> subdomains,
        @QueryParam("aggregate") List<String> aggregates
    ) {
        List<String> filteredApps = filterEmpty(applications);
        List<String> filteredSubs = filterEmpty(subdomains);
        List<String> filteredAggs = filterEmpty(aggregates);

        List<EventType> eventTypes = eventTypeService.findWithFilters(
            filteredApps.isEmpty() ? null : filteredApps,
            filteredSubs.isEmpty() ? null : filteredSubs,
            filteredAggs.isEmpty() ? null : filteredAggs,
            status
        );

        List<BffEventTypeResponse> responses = eventTypes.stream()
            .map(BffEventTypeResponse::from)
            .toList();

        return Response.ok(new BffEventTypeListResponse(responses)).build();
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "Get event type by ID (BFF)")
    public Response getEventType(@PathParam("id") Long id) {
        return eventTypeService.findById(id)
            .map(et -> Response.ok(BffEventTypeResponse.from(et)).build())
            .orElse(Response.status(404)
                .entity(new ErrorResponse("Event type not found: " + id))
                .build());
    }

    @GET
    @Path("/filters/applications")
    @Operation(summary = "Get distinct application names")
    public Response getApplications() {
        List<String> applications = eventTypeService.getDistinctApplications();
        return Response.ok(new FilterOptionsResponse(applications)).build();
    }

    @GET
    @Path("/filters/subdomains")
    @Operation(summary = "Get distinct subdomains")
    public Response getSubdomains(@QueryParam("application") List<String> applications) {
        List<String> filteredApps = filterEmpty(applications);
        List<String> subdomains = eventTypeService.getDistinctSubdomains(filteredApps);
        return Response.ok(new FilterOptionsResponse(subdomains)).build();
    }

    @GET
    @Path("/filters/aggregates")
    @Operation(summary = "Get distinct aggregates")
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
    @Operation(summary = "Create a new event type (BFF)")
    public Response createEventType(CreateEventTypeRequest request) {
        EventType eventType = eventTypeService.execute(new CreateEventType(
            request.code(),
            request.name(),
            request.description()
        ));
        return Response.status(201).entity(BffEventTypeResponse.from(eventType)).build();
    }

    @PATCH
    @Path("/{id}")
    @Operation(summary = "Update an event type (BFF)")
    public Response updateEventType(@PathParam("id") Long id, UpdateEventTypeRequest request) {
        EventType eventType = eventTypeService.execute(new UpdateEventType(
            id,
            request.name(),
            request.description()
        ));
        return Response.ok(BffEventTypeResponse.from(eventType)).build();
    }

    @POST
    @Path("/{id}/schemas")
    @Operation(summary = "Add a schema version (BFF)")
    public Response addSchema(@PathParam("id") Long id, AddSchemaRequest request) {
        EventType eventType = eventTypeService.execute(new AddSchema(
            id,
            request.version(),
            request.mimeType(),
            request.schema(),
            request.schemaType()
        ));
        return Response.status(201).entity(BffEventTypeResponse.from(eventType)).build();
    }

    @POST
    @Path("/{id}/schemas/{version}/finalise")
    @Operation(summary = "Finalise a schema version (BFF)")
    public Response finaliseSchema(@PathParam("id") Long id, @PathParam("version") String version) {
        EventType eventType = eventTypeService.execute(new FinaliseSchema(id, version));
        return Response.ok(BffEventTypeResponse.from(eventType)).build();
    }

    @POST
    @Path("/{id}/schemas/{version}/deprecate")
    @Operation(summary = "Deprecate a schema version (BFF)")
    public Response deprecateSchema(@PathParam("id") Long id, @PathParam("version") String version) {
        EventType eventType = eventTypeService.execute(new DeprecateSchema(id, version));
        return Response.ok(BffEventTypeResponse.from(eventType)).build();
    }

    @POST
    @Path("/{id}/archive")
    @Operation(summary = "Archive an event type (BFF)")
    public Response archiveEventType(@PathParam("id") Long id) {
        EventType eventType = eventTypeService.execute(new ArchiveEventType(id));
        return Response.ok(BffEventTypeResponse.from(eventType)).build();
    }

    @DELETE
    @Path("/{id}")
    @Operation(summary = "Delete an event type (BFF)")
    public Response deleteEventType(@PathParam("id") Long id) {
        eventTypeService.execute(new DeleteEventType(id));
        return Response.noContent().build();
    }

    private List<String> filterEmpty(List<String> list) {
        if (list == null) return List.of();
        return list.stream()
            .filter(s -> s != null && !s.isBlank())
            .toList();
    }

    // ========================================================================
    // BFF DTOs - IDs as Strings for JavaScript precision
    // ========================================================================

    public record BffEventTypeListResponse(List<BffEventTypeResponse> items) {}

    public record BffEventTypeResponse(
        String id,
        String code,
        String name,
        String description,
        EventTypeStatus status,
        String application,
        String subdomain,
        String aggregate,
        String event,
        List<BffSpecVersionResponse> specVersions,
        String createdAt,
        String updatedAt
    ) {
        public static BffEventTypeResponse from(EventType et) {
            String[] parts = et.code.split(":");
            return new BffEventTypeResponse(
                et.id != null ? et.id.toString() : null,
                et.code,
                et.name,
                et.description,
                et.status,
                parts.length > 0 ? parts[0] : null,
                parts.length > 1 ? parts[1] : null,
                parts.length > 2 ? parts[2] : null,
                parts.length > 3 ? parts[3] : null,
                et.specVersions != null
                    ? et.specVersions.stream().map(BffSpecVersionResponse::from).toList()
                    : List.of(),
                et.createdAt != null ? et.createdAt.toString() : null,
                et.updatedAt != null ? et.updatedAt.toString() : null
            );
        }
    }

    public record BffSpecVersionResponse(
        String version,
        String mimeType,
        String schemaType,
        String status
    ) {
        public static BffSpecVersionResponse from(SpecVersion sv) {
            return new BffSpecVersionResponse(
                sv.version(),
                sv.mimeType(),
                sv.schemaType() != null ? sv.schemaType().name() : null,
                sv.status() != null ? sv.status().name() : null
            );
        }
    }

    public record CreateEventTypeRequest(String code, String name, String description) {}
    public record UpdateEventTypeRequest(String name, String description) {}
    public record AddSchemaRequest(String version, String mimeType, String schema, SchemaType schemaType) {}
    public record FilterOptionsResponse(List<String> options) {}
    public record ErrorResponse(String error) {}
}
