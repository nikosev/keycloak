/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.services.resources.admin;

import org.jboss.logging.Logger;
import org.jboss.resteasy.annotations.cache.NoCache;
import org.keycloak.broker.federation.FederationProvider;
import org.keycloak.broker.federation.SAMLFederationProviderFactory;
import org.keycloak.broker.provider.IdentityProviderMapper;
import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;
import org.keycloak.models.*;
import org.keycloak.models.utils.ModelToRepresentation;
import org.keycloak.models.utils.RepresentationToModel;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderFactory;
import org.keycloak.representations.idm.ConfigPropertyRepresentation;
import org.keycloak.representations.idm.FederationMapperRepresentation;
import org.keycloak.representations.idm.IdentityProviderMapperTypeRepresentation;
import org.keycloak.representations.idm.SAMLFederationRepresentation;
import org.keycloak.services.ErrorResponse;
import org.keycloak.services.resources.admin.permissions.AdminPermissionEvaluator;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


public class SAMLFederationResource {

    private final RealmModel realm;
    private final KeycloakSession session;
    private AdminPermissionEvaluator auth;
    private AdminEventBuilder adminEvent;

    protected static final Logger logger = Logger.getLogger(SAMLFederationResource.class);

    public SAMLFederationResource(RealmModel realm, KeycloakSession session, AdminPermissionEvaluator auth, AdminEventBuilder adminEvent) {
        this.realm = realm;
        this.session = session;
        this.auth = auth;
        this.adminEvent = adminEvent.resource(ResourceType.IDENTITY_PROVIDERS_FEDERATION);
    }

    /**
     * Export public broker configuration for federation
     * @param alias federation alias
     * @return
     */
    @GET
    @Path("instances/{alias}/export")
    @NoCache
    public Response export(
            @PathParam("alias") String alias
    ) {
        this.auth.realm().requireViewIdentityProviders();

        FederationModel model = realm.getSAMLFederationByAlias(alias);

        if (model == null) {
            throw new javax.ws.rs.NotFoundException();
        }

        try {
            FederationProvider federationProvider = SAMLFederationProviderFactory.getSAMLFederationProviderFactoryById(session, model.getProviderId()).create(session, model, this.realm.getId());
            return federationProvider.export(session.getContext().getUri(), realm);
        } catch (Exception e) {
            return ErrorResponse.error("Could not export public broker configuration for IdP aggregation [" + model.getProviderId() + "].", Response.Status.NOT_FOUND);
        }

    }


    /**
     * Get a list with all identity provider federations of the realm
     *
     * @return
     */
    @GET
    @Path("instances")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    public List<SAMLFederationRepresentation> list() {
        this.auth.realm().requireViewIdentityProviders();
        return realm.getSAMLFederations().stream().map(ModelToRepresentation::toRepresentation).collect(Collectors.toList());
    }

    /**
     * Create a new identity provider federation
     *
     * @param representation JSON body
     * @return
     */
    @POST
    @Path("instances")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response create(SAMLFederationRepresentation representation) {
        this.auth.realm().requireManageIdentityProviders();

        try {
        	FederationModel model = RepresentationToModel.toModel(representation);
        	FederationProvider federationProvider = SAMLFederationProviderFactory.getSAMLFederationProviderFactoryById(session, model.getProviderId()).create(session,model,this.realm.getId());
        	String id = federationProvider.updateFederation();

			adminEvent.operation(OperationType.CREATE)
					.resourcePath(session.getContext().getUri(), representation.getAlias())
					.representation(representation).success();
			federationProvider.enableUpdateTask();

			return Response.created(session.getContext().getUri().getAbsolutePathBuilder()
					.path(id).build()).build();

        } catch (ModelDuplicateException e) {
            return ErrorResponse.exists("Identity Provider federation" + representation.getAlias() + " already exists");
        }
    }

    @GET
    @Path("instances/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public SAMLFederationRepresentation getIdentityProviderFederation(@PathParam("id") String internalId) {
        this.auth.realm().requireViewIdentityProviders();
        //create alias representation
        FederationModel model = realm.getSAMLFederationById(internalId);
        if (model == null) {
            throw new NotFoundException();
        }
        SAMLFederationRepresentation representation = ModelToRepresentation.toRepresentation(model);
        return representation;
    }

    /**
     * Delete the identity provider federation, along with all its IdPs
     *
     * @return
     */
    @DELETE
    @Path("instances/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @NoCache
    public Response delete(@PathParam("id") String internalId) {
        this.auth.realm().requireManageIdentityProviders();

        FederationModel model = realm.getSAMLFederationById(internalId);
        if (model == null) {
            throw new NotFoundException();
        }
    	FederationProvider federationProvider = SAMLFederationProviderFactory.getSAMLFederationProviderFactoryById(session, model.getProviderId()).create(session,model,this.realm.getId());

        //preremove federation client
        List<ClientModel> existingClientModels = session.clients().getFederationClientsStream(realm, model.getInternalId());
        existingClientModels.stream().forEach(client -> {
            if ( client.getFederations().size() == 1) {
                realm.removeClient(client.getId());
            } else {
                //client exists in more federations
                client.removeFederation(model.getInternalId());
            }
        });
        federationProvider.removeFederation();

        adminEvent.operation(OperationType.DELETE).resourcePath(session.getContext().getUri()).success();
        return Response.noContent().build();
    }

    @GET
    @Path("mapper-types")
    @NoCache
    public Map<String, IdentityProviderMapperTypeRepresentation> getMapperTypes() {
        this.auth.realm().requireViewIdentityProviders();

        KeycloakSessionFactory sessionFactory = session.getKeycloakSessionFactory();
        Map<String, IdentityProviderMapperTypeRepresentation> types = new HashMap<>();
        List<ProviderFactory> factories = sessionFactory.getProviderFactories(IdentityProviderMapper.class);
        for (ProviderFactory factory : factories) {
            IdentityProviderMapper mapper = (IdentityProviderMapper) factory;
            for (String type : mapper.getCompatibleProviders()) {
                if (IdentityProviderMapper.ANY_PROVIDER.equals(type) || type.equals("saml")) {
                    IdentityProviderMapperTypeRepresentation rep = new IdentityProviderMapperTypeRepresentation();
                    rep.setId(mapper.getId());
                    rep.setCategory(mapper.getDisplayCategory());
                    rep.setName(mapper.getDisplayType());
                    rep.setHelpText(mapper.getHelpText());
                    List<ProviderConfigProperty> configProperties = mapper.getConfigProperties();
                    for (ProviderConfigProperty prop : configProperties) {
                        ConfigPropertyRepresentation propRep = ModelToRepresentation.toRepresentation(prop);
                        rep.getProperties().add(propRep);
                    }
                    types.put(rep.getId(), rep);
                    break;
                }
            }
        }
        return types;
    }


    @GET
    @Path("instances/{id}/mappers")
    @Produces(MediaType.APPLICATION_JSON)
    public List<FederationMapperRepresentation> getIdentityProviderFederationMappers(@PathParam("id") String id) {
        this.auth.realm().requireViewIdentityProviders();
        return realm.getIdentityProviderFederationMappers(id).stream().map(ModelToRepresentation::toRepresentation).collect(Collectors.toList());
    }

    @POST
    @Path("instances/{id}/mappers")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createMapper(@PathParam("id") String id, FederationMapperRepresentation representation) {
        this.auth.realm().requireManageIdentityProviders();

        FederationMapperModel model = RepresentationToModel.toModel(representation);
        model.setFederationId(id);
        try {
            realm.addIdentityProvidersFederationMapper(model);
        } catch (Exception e) {
            return ErrorResponse.error("Failed to add federation mapper '" + model.getName() + "'", Response.Status.BAD_REQUEST);
        }

        adminEvent.operation(OperationType.CREATE)
                .resourcePath(session.getContext().getUri(), representation.getId())
                .representation(representation).success();
        return Response.created(session.getContext().getUri().getAbsolutePathBuilder().path(model.getId()).build()).build();
    }

    @GET
    @Path("instances/{id}/mappers/{mapperId}")
    @Produces(MediaType.APPLICATION_JSON)
    public FederationMapperRepresentation getIdentityProviderFederationMapper(@PathParam("id") String id, @PathParam("mapperId") String mapperId) {
        this.auth.realm().requireViewIdentityProviders();
        FederationMapperModel mapper = realm.getIdentityProviderFederationMapper(id, mapperId);
        if (mapper == null) throw new NotFoundException("mapper not found");
        return ModelToRepresentation.toRepresentation(mapper);
    }

    /**
     * Update a mapper for the identity provider federation
     *
     * @param id Mapper id
     */
    @PUT
    @NoCache
    @Path("instances/{id}/mappers/{mapperId}")
    @Consumes(MediaType.APPLICATION_JSON)
    public void updateMapper(@PathParam("id") String id, @PathParam("mapperId") String mapperId, FederationMapperRepresentation representation) {
        this.auth.realm().requireManageIdentityProviders();


        FederationMapperModel model = realm.getIdentityProviderFederationMapper(id, mapperId);
        if (model == null) throw new NotFoundException("Model not found");
        model = RepresentationToModel.toModel(representation);
        realm.updateIdentityProvidersFederationMapper(model);
        adminEvent.operation(OperationType.UPDATE).resource(ResourceType.IDENTITY_PROVIDERS_FEDERATION).resourcePath(session.getContext().getUri()).representation(representation).success();

    }

    @POST
    @Path("instances/{id}/mappers/{mapperId}/idp/{action}")
    public void massIdPMapperAction(@PathParam("id") String id, @PathParam("mapperId") String mapperId, @PathParam("action") String action) {
        this.auth.realm().requireManageIdentityProviders();

        FederationMapperModel mapper = realm.getIdentityProviderFederationMapper(id, mapperId);
        if (mapper == null) throw new NotFoundException("mapper not found");
        List<String> idpAlias = realm.getIdentityProvidersByFederation(id);

        if (action.equals("add")) {
            idpAlias.stream().forEach(alias -> {
                try {
                    realm.addIdentityProviderMapper(new IdentityProviderMapperModel(mapper, alias));
                } catch (Exception e) {
                    logger.info("Mapper with id = " + mapperId + " failed to add to Idp with alias = " + alias);
                }
            });
        } else if (action.equals("update")) {
            idpAlias.stream().forEach(alias -> {
                try {
                    IdentityProviderMapperModel idpMapper = realm.getIdentityProviderMapperByName(alias, mapper.getName());
                    if (idpMapper != null) {
                        idpMapper.setIdentityProviderMapper(mapper.getIdentityProviderMapper());
                        idpMapper.setConfig(mapper.getConfig());
                        realm.updateIdentityProviderMapper(idpMapper);
                    }
                } catch (Exception e) {
                    logger.info("Mapper with id = " + mapperId + " failed to update in the Idp with alias = " + alias);
                }
            });
        } else if (action.equals("remove")) {
            idpAlias.stream().forEach(alias -> {
                try {
                    IdentityProviderMapperModel idpMapper = realm.getIdentityProviderMapperByName(alias, mapper.getName());
                    if (idpMapper != null)
                        realm.removeIdentityProviderMapper(idpMapper);
                } catch (Exception e) {
                    logger.info("Mapper with id = " + mapperId + " failed to remove from Idp with alias = " + alias);
                }
            });
        } else {
            throw new BadRequestException("This action to federation mapper does not exist!");
        }
    }

    /**
     * Delete a mapper for the identity provider federation
     *
     * @param id Mapper id
     */
    @DELETE
    @NoCache
    @Path("instances/{id}/mappers/{mapperId}")
    public void deleteMapper(@PathParam("id") String id, @PathParam("mapperId") String mapperId) {
        this.auth.realm().requireManageIdentityProviders();

        realm.removeIdentityProvidersFederationMapper(mapperId,id);
        adminEvent.operation(OperationType.DELETE).resource(ResourceType.IDENTITY_PROVIDERS_FEDERATION).resourcePath(session.getContext().getUri()).success();

    }


}