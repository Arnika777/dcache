package org.dcache.restful.qos;

import org.json.JSONArray;
import org.json.JSONObject;

import javax.ws.rs.Path;
import javax.ws.rs.GET;
import javax.ws.rs.Produces;
import javax.ws.rs.PathParam;

import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotAuthorizedException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.ForbiddenException;

import javax.ws.rs.core.MediaType;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;


import diskCacheV111.util.CacheException;
import diskCacheV111.util.PermissionDeniedCacheException;

import org.dcache.auth.Subjects;
import org.dcache.restful.util.ServletContextHandlerAttributes;


/**
 * RestFul API for querying and manipulating QoS
 */


@Path("/qos-management/qos/")
public class QosManagement {

    public static final String DISK = "disk";
    public static final String TAPE = "tape";
    public static final String DISK_TAPE = "disk+tape";
    public static final String VOLATILE = "volatile";
    public static final String UNAVAILABLE = "unavailable";

    public static List<String> cdmi_geographic_placement_provided = Arrays.asList("DE");

    /**
     * Get the list of available QoS for file  and directory objects corresponding to some specific quality of services supported by dCache back-end.
     * For example, storage requirements such as flexible allocation of disk or tape storage space.
     *
     * @param qosValue specific object (file | directory)
     * @return JSONObject List of available QoS
     * @throws CacheException
     */

    @GET
    @Path("{qosValue : .*}")
    @Produces(MediaType.APPLICATION_JSON)
    public String getQosList(@PathParam("qosValue") String qosValue) throws CacheException {

        JSONObject json = new JSONObject();


        try {

            if (Subjects.isNobody(ServletContextHandlerAttributes.getSubject())) {
                throw new PermissionDeniedCacheException("Permission denied");
            }

            // query the lis of available QoS for file objects
            if ("file".equals(qosValue)) {
                JSONArray list = new JSONArray(Arrays.asList(DISK, TAPE, DISK_TAPE, VOLATILE));
                json.put("name", list);
            }
            // query the lis of available QoS for directory objects
            else if ("directory".equals(qosValue.trim())) {
                JSONArray list = new JSONArray(Arrays.asList(DISK, TAPE, DISK_TAPE, VOLATILE));
                json.put("name", list);
            } else {
                throw new NotFoundException();
            }

            json.put("status", "200");
            json.put("message", "successful");

        } catch (PermissionDeniedCacheException e) {
            if (Subjects.isNobody(ServletContextHandlerAttributes.getSubject())) {
                throw new NotAuthorizedException(e);
            } else {
                throw new ForbiddenException(e);
            }
        } catch (CacheException e) {
            throw new InternalServerErrorException(e);
        }

        return json.toString();
    }


    /**
     * Query data for specified QoS for file objects.
     * All QoS objects for file objects are specified by "name", "transition" attributes.
     * If present, the QoS objects should also contain data system metadata values which are described in the following sections.
     *
     * @param qosValue specific QoS {disk|tape|disk+tape}
     * @return BackendCapabilityDisk  detailed description of the QoS
     * @throws CacheException
     */


    @GET
    @Path("/file/{qosValue : .*}")
    @Produces(MediaType.APPLICATION_JSON)
    public BackendCapabilityResponse getQueriedQosForFiles(@PathParam("qosValue") String qosValue) throws CacheException {

        BackendCapabilityResponse backendCapabilityResponse = new BackendCapabilityResponse();
        BackendCapability backendCapability = new BackendCapability();

        try {

            if (Subjects.isNobody(ServletContextHandlerAttributes.getSubject())) {
                throw new PermissionDeniedCacheException("Permission denied");
            }

            backendCapabilityResponse.setStatus("200");
            backendCapabilityResponse.setMessage("successful");

            // Set data and metadata for "DISK" QoS
            if (DISK.equals(qosValue)) {

                QoSMetadata qoSMetadata = new QoSMetadata("1", cdmi_geographic_placement_provided, "100");
                setBackendCapability(backendCapability, DISK, Arrays.asList(TAPE, DISK_TAPE), qoSMetadata);
            }
            // Set data and metadata for "TAPE" QoS
            else if (TAPE.equals(qosValue)) {

                QoSMetadata qoSMetadata = new QoSMetadata("1", cdmi_geographic_placement_provided, "600000");
                setBackendCapability(backendCapability, TAPE, Arrays.asList(DISK_TAPE), qoSMetadata);

            }
            // Set data and metadata for "Disk & TAPE" QoS
            else if (DISK_TAPE.equals(qosValue)) {

                QoSMetadata qoSMetadata = new QoSMetadata("2", cdmi_geographic_placement_provided, "100");
                setBackendCapability(backendCapability, DISK_TAPE, Arrays.asList(TAPE), qoSMetadata);

            }
            else if (VOLATILE.equals(qosValue)) {
                QoSMetadata qoSMetadata = new QoSMetadata("0", cdmi_geographic_placement_provided, "100");
                setBackendCapability(backendCapability, VOLATILE, Arrays.asList(DISK), qoSMetadata);
            }
            // The QoS is not known or supported.
            else {
                throw new NotFoundException();
            }

        } catch (PermissionDeniedCacheException e) {
            if (Subjects.isNobody(ServletContextHandlerAttributes.getSubject())) {
                throw new NotAuthorizedException(e);
            } else {
                throw new ForbiddenException(e);
            }
        } catch (CacheException e) {
            throw new InternalServerErrorException(e);
        }

        backendCapabilityResponse.setBackendCapability(backendCapability);

        return backendCapabilityResponse;
    }


    /**
     * Query data for specified QoS for directory objects.
     *
     * @param qosValue specific QoS {disk|tape}
     * @return BackendCapabilityDisk  detailed description of the QoS
     * @throws CacheException
     */


    @GET
    @Path("/directory/{qosValue : .*}")
    @Produces(MediaType.APPLICATION_JSON)
    public BackendCapabilityResponse getQueriedQosForDirectories(@PathParam("qosValue") String qosValue) throws CacheException {

        BackendCapabilityResponse backendCapabilityResponse = new BackendCapabilityResponse();

        BackendCapability backendCapability = new BackendCapability();

        try {

            if (Subjects.isNobody(ServletContextHandlerAttributes.getSubject())) {
                throw new PermissionDeniedCacheException("Permission denied");
            }

            backendCapabilityResponse.setStatus("200");
            backendCapabilityResponse.setMessage("successful");

            // Set data and metadata for "DISK" QoS
            if (DISK.equals(qosValue)) {

                QoSMetadata qoSMetadata = new QoSMetadata("1", cdmi_geographic_placement_provided, "100");
                setBackendCapability(backendCapability, DISK, Arrays.asList(TAPE), qoSMetadata);
            }
            // Set data and metadata for "TAPE" QoS
            else if (TAPE.equals(qosValue)) {

                QoSMetadata qoSMetadata = new QoSMetadata("1", cdmi_geographic_placement_provided, "600000");
                setBackendCapability(backendCapability, TAPE, Arrays.asList(DISK), qoSMetadata);
            }
            // Set data and metadata for "Disk & TAPE" QoS
            else if (DISK_TAPE.equals(qosValue)) {
                QoSMetadata qoSMetadata = new QoSMetadata("2", cdmi_geographic_placement_provided, "100");
                setBackendCapability(backendCapability, DISK_TAPE, Collections.emptyList(), qoSMetadata);
            }
            else if (VOLATILE.equals(qosValue)) {
                QoSMetadata qoSMetadata = new QoSMetadata("0", cdmi_geographic_placement_provided, "100");
                setBackendCapability(backendCapability, VOLATILE, Collections.emptyList(), qoSMetadata);
            }
            // The QoS is not known or supported.
            else {
                throw new NotFoundException();
            }

        } catch (PermissionDeniedCacheException e) {
            if (Subjects.isNobody(ServletContextHandlerAttributes.getSubject())) {
                throw new NotAuthorizedException(e);
            } else {
                throw new ForbiddenException(e);
            }
        } catch (CacheException e) {
            throw new InternalServerErrorException(e);
        }

        backendCapabilityResponse.setBackendCapability(backendCapability);
        return backendCapabilityResponse;
    }


    public void setBackendCapability(BackendCapability backendCapability,
                                     String name,
                                     List<String> transitions,
                                     QoSMetadata qoSMetadata) {

        backendCapability.setName(name);
        backendCapability.setTransition(transitions);
        backendCapability.setMetadata(qoSMetadata);
    }


}
