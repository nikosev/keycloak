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
package org.keycloak.broker.saml.federation;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.net.URI;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;
import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.namespace.QName;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLStreamWriter;

import org.jboss.logging.Logger;
import org.keycloak.broker.federation.AbstractIdPFederationProvider;
import org.keycloak.broker.provider.IdentityProviderMapper;
import org.keycloak.broker.saml.SAMLIdentityProviderConfig;
import org.keycloak.common.util.PemUtils;
import org.keycloak.connections.httpclient.HttpClientProvider;
import org.keycloak.crypto.Algorithm;
import org.keycloak.crypto.KeyStatus;
import org.keycloak.crypto.KeyUse;
import org.keycloak.dom.saml.v2.assertion.AttributeType;
import org.keycloak.dom.saml.v2.metadata.AttributeConsumingServiceType;
import org.keycloak.dom.saml.v2.metadata.EndpointType;
import org.keycloak.dom.saml.v2.metadata.EntitiesDescriptorType;
import org.keycloak.dom.saml.v2.metadata.EntityDescriptorType;
import org.keycloak.dom.saml.v2.metadata.IDPSSODescriptorType;
import org.keycloak.dom.saml.v2.metadata.KeyDescriptorType;
import org.keycloak.dom.saml.v2.metadata.KeyTypes;
import org.keycloak.dom.saml.v2.metadata.LocalizedNameType;
import org.keycloak.dom.saml.v2.metadata.OrganizationType;
import org.keycloak.dom.saml.v2.metadata.SPSSODescriptorType;
import org.keycloak.models.*;
import org.keycloak.models.utils.DefaultAuthenticationFlows;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.models.utils.ModelToRepresentation;
import org.keycloak.models.utils.RepresentationToModel;
import org.keycloak.protocol.ProtocolMapperUtils;
import org.keycloak.protocol.saml.EntityDescriptorDescriptionConverter;
import org.keycloak.protocol.saml.SamlClient;
import org.keycloak.protocol.saml.SamlConfigAttributes;
import org.keycloak.protocol.saml.SamlProtocol;
import org.keycloak.protocol.saml.SamlService;
import org.keycloak.protocol.saml.mappers.AttributeStatementHelper;
import org.keycloak.protocol.saml.mappers.SamlMetadataDescriptorUpdater;
import org.keycloak.protocol.saml.mappers.UserAttributeStatementMapper;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;
import org.keycloak.saml.SPMetadataDescriptor;
import org.keycloak.saml.SignatureAlgorithm;
import org.keycloak.saml.common.constants.GeneralConstants;
import org.keycloak.saml.common.constants.JBossSAMLURIConstants;
import org.keycloak.saml.common.exceptions.ConfigurationException;
import org.keycloak.saml.common.exceptions.ParsingException;
import org.keycloak.saml.common.exceptions.ProcessingException;
import org.keycloak.saml.common.util.DocumentUtil;
import org.keycloak.saml.common.util.StaxUtil;
import org.keycloak.saml.common.util.XmlKeyInfoKeyNameTransformer;
import org.keycloak.saml.processing.api.saml.v2.sig.SAML2Signature;
import org.keycloak.saml.processing.core.parsers.saml.SAMLParser;
import org.keycloak.saml.processing.core.saml.v2.util.SAMLMetadataUtil;
import org.keycloak.saml.processing.core.saml.v2.writers.SAMLMetadataWriter;
import org.keycloak.services.managers.ClientManager;
import org.keycloak.services.scheduled.ClusterAwareScheduledTaskRunner;
import org.keycloak.services.scheduled.UpdateFederation;
import org.keycloak.timer.TimerProvider;
import org.keycloak.validation.ValidationUtil;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;


public class SAMLFederationProvider extends AbstractIdPFederationProvider <SAMLFederationModel> {

	protected static final Logger logger = Logger.getLogger(SAMLFederationProvider.class);
	private static final String CATEGORY_CLIENTS= "Clients";
	private static final String CATEGORY_IDPS= "Identity Providers";
	private List<ProtocolMapperModel> defaultSAMLMappers;

	public SAMLFederationProvider(KeycloakSession session, SAMLFederationModel model, String realmId) {
		super(session, model,realmId);
	}

	@Override
    public Set<String> parseIdps(KeycloakSession session, InputStream inputStream) {
        try {
            Object parsedObject = SAMLParser.getInstance().parse(inputStream);
            EntitiesDescriptorType entitiesDescriptorType = (EntitiesDescriptorType) parsedObject;
            List<EntityDescriptorType> entities = (List<EntityDescriptorType>) (Object) entitiesDescriptorType.getEntityDescriptor();
            Set<String> idpIDs = new HashSet<String>();
            for(EntityDescriptorType entity : entities) {
                idpIDs.add(entity.getEntityID());
            }
            return idpIDs;
        } catch (ParsingException pe) {
            throw new RuntimeException("Could not parse IdP SAML Metadata", pe);
        }
    }

	@Override
	public String updateFederation() {
		RealmModel realm = session.realms().getRealm(realmId);
		FederationModel oldModel = realm.getSAMLFederationById(model.getInternalId());
		if (oldModel == null) {
			model.setInternalId(KeycloakModelUtils.generateId());
			realm.addSAMLFederation(model);
		} else {
			realm.updateSAMLFederation(model);
			if (!model.getUpdateFrequencyInMins().equals(oldModel.getUpdateFrequencyInMins())) {
                enableUpdateTask();
            }
		}
		return model.getInternalId();
	}

	@Override
	public void enableUpdateTask() {
		logger.info("Enabling update task of federation with id: " + model.getInternalId());
		if(model.getLastMetadataRefreshTimestamp()==null) {
            model.setLastMetadataRefreshTimestamp(0L);
        }
		// remove previous task and add new with new RefreshEveryHours
		TimerProvider timer = session.getProvider(TimerProvider.class);
		timer.cancelTask("UpdateFederation" + model.getInternalId());
		UpdateFederation updateFederation = new UpdateFederation(model.getInternalId(),realmId);
		ClusterAwareScheduledTaskRunner taskRunner = new ClusterAwareScheduledTaskRunner(session.getKeycloakSessionFactory(), updateFederation,model.getUpdateFrequencyInMins() * 60 * 1000);
		long delay = (model.getUpdateFrequencyInMins() * 60 * 1000) - Instant.now().toEpochMilli() + model.getLastMetadataRefreshTimestamp();
		timer.schedule(taskRunner, delay > 60 * 1000 ? delay : 60 * 1000, model.getUpdateFrequencyInMins() * 60 * 1000, "UpdateFederation" + model.getInternalId());
		logger.info("Finished setting up the update task of federation with id: " + model.getInternalId());
	}

	@Override
	public synchronized void updateSamlEntities() {

		logger.info("Started updating the SAML federation (id): " + model.getInternalId());

		RealmModel realm = session.realms().getRealm(realmId);

		List<EntityDescriptorType> entities = new ArrayList<EntityDescriptorType>();
		Date validUntil = null;
		try {
			InputStream inputStream = session.getProvider(HttpClientProvider.class).get(model.getUrl());
			Object parsedObject = SAMLParser.getInstance().parse(inputStream);
			EntitiesDescriptorType entitiesDescriptorType = (EntitiesDescriptorType) parsedObject;
			validUntil = entitiesDescriptorType.getValidUntil().toGregorianCalendar().getTime();
			model.setValidUntilTimestamp(validUntil.getTime());
	        entities = (List<EntityDescriptorType>) (Object) entitiesDescriptorType.getEntityDescriptor();
		} catch (ParsingException | IOException e) {
			e.printStackTrace();
		}

		if(entities.isEmpty())
         {
            return; //add a log entry for the failure reason and/or write it in the database, so you can alert later on the admins through the UI
        }

		//default language
		final String preferredLang = realm.getDefaultLocale() != null ? realm.getDefaultLocale():"en";

		//default authedication flow model
		AuthenticationFlowModel flowModel = realm.getFlowByAlias(DefaultAuthenticationFlows.FIRST_BROKER_LOGIN_FLOW);
        if (flowModel == null) {
            throw new ModelException("No available authentication flow with alias: " + DefaultAuthenticationFlows.FIRST_BROKER_LOGIN_FLOW);
        }

        List<IdentityProviderModel> addedIdps= new ArrayList<>();
		List<IdentityProviderModel> updatedIdps= new ArrayList<>();
		List<String> existingIdps = realm.getIdentityProvidersByFederation(model.getInternalId());
		List<ClientModel> existingClientModels = session.clients().getFederationClientsStream(realm, model.getInternalId());
        //values = "All" or "Identity Providers" or "Clients"
		String category = model.getCategory();
		defaultSAMLMappers = CATEGORY_IDPS.equals(category) ?
				null :
				realm.getDefaultClientScopesStream(false).filter(scope -> "saml".equals(scope.getProtocol())).flatMap(scope -> scope.getProtocolMappersStream().filter(mapper -> UserAttributeStatementMapper.PROVIDER_ID.equals(mapper.getProtocolMapper()))).distinct().collect(Collectors.toList());


		for(EntityDescriptorType entity: entities) {

            if (!parseEntity(entity)) {
                continue;
            }

			IDPSSODescriptorType idpDescriptor = null;
			SPSSODescriptorType spDescriptorType = null;

			// Metadata documents can contain multiple Descriptors (See ADFS metadata
			// documents) such as RoleDescriptor, SPSSODescriptor, IDPSSODescriptor.
			// So we need to loop through to find the IDPSSODescriptor.
			for (EntityDescriptorType.EDTChoiceType edtChoiceType : entity.getChoiceType()) {
				List<EntityDescriptorType.EDTDescriptorChoiceType> descriptors = edtChoiceType.getDescriptors();

				if (!CATEGORY_CLIENTS.equals(category) && !descriptors.isEmpty() && descriptors.get(0).getIdpDescriptor() != null) {
					idpDescriptor = descriptors.get(0).getIdpDescriptor();
				}

				if (!CATEGORY_IDPS.equals(category) && !descriptors.isEmpty() && descriptors.get(0).getSpDescriptor() != null) {
					spDescriptorType = descriptors.get(0).getSpDescriptor();
				}
			}

			if (idpDescriptor != null) {

				try {
					//Idp parsing
					String alias = getHash(entity.getEntityID());
					IdentityProviderModel identityProviderModel = null;

					//check if this federation has already included this IdP
					if (existingIdps.contains(alias)) {
						identityProviderModel = new SAMLIdentityProviderConfig(
								realm.getIdentityProviderByAlias(alias));
						existingIdps.remove(alias);
					} else {

						// check if Idp exists in database
						IdentityProviderModel previous = realm.getIdentityProviderByAlias(alias);
						if (previous != null) {
							identityProviderModel = new SAMLIdentityProviderConfig(previous);
						} else {
							// initialize idp values
							// set alias and default values
							identityProviderModel = new SAMLIdentityProviderConfig();
							identityProviderModel.setProviderId(model.getProviderId());
							identityProviderModel.setAlias(alias);
							// put default parameters
							Map<String, String> config = new HashMap<>();
							config.put(SAMLIdentityProviderConfig.ADD_EXTENSIONS_ELEMENT_WITH_KEY_INFO, "false");
							config.put(SAMLIdentityProviderConfig.SIGNATURE_ALGORITHM, "RSA_SHA256");
							config.put(SAMLIdentityProviderConfig.XML_SIG_KEY_INFO_KEY_NAME_TRANSFORMER, "KEY_ID");

							config.put(IdentityProviderModel.SYNC_MODE, model.getConfig().get(IdentityProviderModel.SYNC_MODE));
							config.put("loginHint", "false");

							config.put(SAMLIdentityProviderConfig.WANT_ASSERTIONS_ENCRYPTED, String.valueOf(model.isWantAssertionsEncrypted()));
							config.put(SAMLIdentityProviderConfig.WANT_ASSERTIONS_SIGNED, String.valueOf(model.isWantAssertionsSigned()));
							config.put(SAMLIdentityProviderConfig.WANT_LOGOUT_REQUESTS_SIGNED, String.valueOf(model.isWantLogoutRequestsSigned()));
							config.put(SAMLIdentityProviderConfig.SP_ENTITY_ID, model.getConfig().get(SAMLIdentityProviderConfig.SP_ENTITY_ID));

							config.put(SAMLIdentityProviderConfig.POST_BINDING_RESPONSE, String.valueOf(model.isPostBindingResponse()));
							config.put(SAMLIdentityProviderConfig.POST_BINDING_LOGOUT, String.valueOf(model.isPostBindingLogoutReceivingRequest()));

							config.put("promotedLoginbutton", "false");
							identityProviderModel.setConfig(config);

							identityProviderModel.setFirstBrokerLoginFlowId(flowModel.getId());
						}
						identityProviderModel.addFederation(model.getInternalId());
					}
					parseIdP(identityProviderModel, validUntil, entity, idpDescriptor, preferredLang);

					identityProviderModel.validate(realm);
					if (identityProviderModel.getInternalId() == null) {
						addedIdps.add(identityProviderModel);
					} else {
						updatedIdps.add(identityProviderModel);
					}
				} catch (Exception ex) {
					ex.printStackTrace();
					logger.warnf("Federation: %s -> Could not insert the identity provider with entityId: %s", model.getDisplayName(), entity.getEntityID());
				}
			}

			if (spDescriptorType != null) {
				//client parsing
				try {
					ClientModel clientModel = existingClientModels.stream().filter(client -> entity.getEntityID().equals(client.getClientId())).findFirst().orElse(null);
					if (clientModel == null) {
						ClientModel clientWithSameClientId = realm.getClientByClientId(entity.getEntityID());
						if (clientWithSameClientId == null) {
							ClientRepresentation clientRep = createClientDefaultValues(entity.getEntityID());
							List<String> federations = new ArrayList<>();
							federations.add(model.getInternalId());
							clientRep.setFederations(federations);
							parseClient(clientRep, spDescriptorType, entity.getOrganization(), validUntil, preferredLang);
							ClientModel clientModelCreate = ClientManager.createClient(session, realm, clientRep);

							ValidationUtil.validateClient(session, clientModelCreate, null, true, r -> {
								session.getTransactionManager().setRollbackOnly();
								throw new RuntimeException("Invalid client " + clientRep.getClientId() + ": " + r.getAllErrorsAsString());
							});
						} else {
							ClientRepresentation clientRep = ModelToRepresentation.toRepresentation(clientWithSameClientId, session);
							clientRep.getFederations().add(model.getInternalId());
							updateClient(clientRep, clientWithSameClientId, spDescriptorType, entity.getOrganization(), validUntil, preferredLang);
						}
					} else {
						//update client model
						existingClientModels.removeIf(client -> clientModel.getId().equals(client.getId()));
						ClientRepresentation clientRep = ModelToRepresentation.toRepresentation(clientModel, session);
						updateClient(clientRep, clientModel, spDescriptorType, entity.getOrganization(), validUntil, preferredLang);
					}

				} catch (Exception ex) {
					ex.printStackTrace();
					logger.warnf("Federation: %s -> Could not insert the client provider with entityId: %s", model.getDisplayName(), entity.getEntityID());
				}
			}

		}

		existingClientModels.stream().forEach(client -> {
			if ( client.getFederations().size() == 1) {
				realm.removeClient(client.getId());
			} else {
				//client exists in more federations
				client.removeFederation(model.getInternalId());
			}
		});

		model.setLastMetadataRefreshTimestamp(new Date().getTime());
	    realm.taskExecutionFederation(model, addedIdps, updatedIdps, existingIdps);

		logger.info("Finished updating IdPs of federation (id): " + model.getInternalId());
	}

	private ClientRepresentation createClientDefaultValues(String clientId){
		ClientRepresentation app = new ClientRepresentation();
		app.setClientId(clientId);
		app.setFullScopeAllowed(true);
		app.setProtocol(SamlProtocol.LOGIN_PROTOCOL);
		Map<String, String> attributes = new HashMap<>();
		attributes.put(SamlConfigAttributes.SAML_SERVER_SIGNATURE, SamlProtocol.ATTRIBUTE_TRUE_VALUE); // default to true
		attributes.put(SamlConfigAttributes.SAML_SERVER_SIGNATURE_KEYINFO_EXT, SamlProtocol.ATTRIBUTE_FALSE_VALUE); // default to false
		attributes.put(SamlConfigAttributes.SAML_SIGNATURE_ALGORITHM, SignatureAlgorithm.RSA_SHA256.toString());
		attributes.put(SamlConfigAttributes.SAML_AUTHNSTATEMENT, SamlProtocol.ATTRIBUTE_TRUE_VALUE);
		attributes.put(SamlConfigAttributes.SAML_CANONICALIZATION_METHOD_ATTRIBUTE, JBossSAMLURIConstants.TRANSFORM_C14N_EXCL.getUri().toString());
		app.setAttributes(attributes);
		return app;
	}

	private void parseClient(ClientRepresentation app, SPSSODescriptorType spDescriptorType, OrganizationType organization, Date validUntil, String preferredLang) {
		String name =null;
		String description = null;
		if (spDescriptorType.getExtensions() != null
				&& spDescriptorType.getExtensions().getUIInfo() != null ) {
			name = spDescriptorType.getExtensions().getUIInfo().getDisplayName().stream()
					.filter(dn -> preferredLang.equals(dn.getLang())).findAny().orElse(new LocalizedNameType(preferredLang)).getValue();
			description = spDescriptorType.getExtensions().getUIInfo().getDescription().stream()
					.filter(dn -> preferredLang.equals(dn.getLang())).findAny().orElse(new LocalizedNameType(preferredLang)).getValue();
		}
		if ( name == null && organization!= null)
			name = organization.getOrganizationDisplayName().stream()
					.filter(dn -> preferredLang.equals(dn.getLang())).findAny().orElse(new LocalizedNameType(preferredLang)).getValue();
		if (name != null) {
			app.setName(name);
		}
		if (description != null) {
			app.setDescription(description);
		}

		app.setEnabled(validUntil!= null &&  validUntil.before(new Date()) ? false : true);
		List<String> redirectUris = new LinkedList<>();
		if (spDescriptorType.isWantAssertionsSigned()) {
			app.getAttributes().put(SamlConfigAttributes.SAML_ASSERTION_SIGNATURE, SamlProtocol.ATTRIBUTE_TRUE_VALUE);
		} else {
			app.getAttributes().put(SamlConfigAttributes.SAML_ASSERTION_SIGNATURE, SamlProtocol.ATTRIBUTE_FALSE_VALUE);
		}
		String logoutPost = EntityDescriptorDescriptionConverter.getLogoutLocation(spDescriptorType, JBossSAMLURIConstants.SAML_HTTP_POST_BINDING.get());
		if (logoutPost != null) app.getAttributes().put(SamlProtocol.SAML_SINGLE_LOGOUT_SERVICE_URL_POST_ATTRIBUTE, logoutPost);
		String logoutRedirect = EntityDescriptorDescriptionConverter.getLogoutLocation(spDescriptorType, JBossSAMLURIConstants.SAML_HTTP_REDIRECT_BINDING.get());
		if (logoutRedirect != null) app.getAttributes().put(SamlProtocol.SAML_SINGLE_LOGOUT_SERVICE_URL_REDIRECT_ATTRIBUTE, logoutRedirect);

		String assertionConsumerServicePostBinding = EntityDescriptorDescriptionConverter.getServiceURL(spDescriptorType, JBossSAMLURIConstants.SAML_HTTP_POST_BINDING.get());
		if (assertionConsumerServicePostBinding != null) {
			app.getAttributes().put(SamlProtocol.SAML_ASSERTION_CONSUMER_URL_POST_ATTRIBUTE, assertionConsumerServicePostBinding);
			redirectUris.add(assertionConsumerServicePostBinding);
		}
		String assertionConsumerServiceRedirectBinding = EntityDescriptorDescriptionConverter.getServiceURL(spDescriptorType, JBossSAMLURIConstants.SAML_HTTP_REDIRECT_BINDING.get());
		if (assertionConsumerServiceRedirectBinding != null) {
			app.getAttributes().put(SamlProtocol.SAML_ASSERTION_CONSUMER_URL_REDIRECT_ATTRIBUTE, assertionConsumerServiceRedirectBinding);
			redirectUris.add(assertionConsumerServiceRedirectBinding);
		}
		String assertionConsumerServiceSoapBinding = EntityDescriptorDescriptionConverter.getServiceURL(spDescriptorType, JBossSAMLURIConstants.SAML_SOAP_BINDING.get());
		if (assertionConsumerServiceSoapBinding != null) {
			redirectUris.add(assertionConsumerServiceSoapBinding);
		}
		String assertionConsumerServicePaosBinding = EntityDescriptorDescriptionConverter.getServiceURL(spDescriptorType, JBossSAMLURIConstants.SAML_PAOS_BINDING.get());
		if (assertionConsumerServicePaosBinding != null) {
			redirectUris.add(assertionConsumerServicePaosBinding);
		}
		String assertionConsumerServiceArtifactBinding = EntityDescriptorDescriptionConverter.getServiceURL(spDescriptorType, JBossSAMLURIConstants.SAML_HTTP_ARTIFACT_BINDING.get());
		if (assertionConsumerServiceArtifactBinding != null) {
			app.getAttributes().put(SamlProtocol.SAML_ASSERTION_CONSUMER_URL_ARTIFACT_ATTRIBUTE, assertionConsumerServiceArtifactBinding);
			redirectUris.add(assertionConsumerServiceArtifactBinding);
		}
		String artifactResolutionService = EntityDescriptorDescriptionConverter.getArtifactResolutionService(spDescriptorType);
		if (artifactResolutionService != null) {
			app.getAttributes().put(SamlProtocol.SAML_ARTIFACT_RESOLUTION_SERVICE_URL_ATTRIBUTE, artifactResolutionService);
		}

		if (spDescriptorType.getNameIDFormat() != null) {
			for (String format : spDescriptorType.getNameIDFormat()) {
				String attribute = SamlClient.samlNameIDFormatToClientAttribute(format);
				if (attribute != null) {
					app.getAttributes().put(SamlConfigAttributes.SAML_NAME_ID_FORMAT_ATTRIBUTE, attribute);
					break;
				}
			}
		}

		if (spDescriptorType.getExtensions() != null && spDescriptorType.getExtensions().getUIInfo() != null) {
			if (!spDescriptorType.getExtensions().getUIInfo().getLogo().isEmpty()) {
				app.getAttributes().put(ClientModel.LOGO_URI, spDescriptorType.getExtensions().getUIInfo().getLogo().get(0).getValue().toString());
			}
			if (!spDescriptorType.getExtensions().getUIInfo().getPrivacyStatementURL().isEmpty()) {
				app.getAttributes().put(ClientModel.POLICY_URI, spDescriptorType.getExtensions().getUIInfo().getPrivacyStatementURL().stream().filter(dn -> "en".equals(dn.getLang())).findFirst().orElse(spDescriptorType.getExtensions().getUIInfo().getPrivacyStatementURL().get(0)).getValue().toString());
			}
		}

		if (spDescriptorType.getAttributeConsumingService().isEmpty()) {
			app.setProtocolMappers(defaultSAMLMappers.stream().map(mapper ->{
				ProtocolMapperRepresentation mapperRep = ModelToRepresentation.toRepresentation(mapper);
				mapperRep.setId(null);
				return mapperRep;
			}).collect(Collectors.toList()));
		} else {
			List<ProtocolMapperRepresentation> mappers = new ArrayList<>();
			spDescriptorType.getAttributeConsumingService().stream().flatMap(att -> att.getRequestedAttribute().stream())
					.forEach(attr -> {
						ProtocolMapperModel mapperModel = defaultSAMLMappers.stream().filter(mapper -> mapper.getConfig().get(AttributeStatementHelper.SAML_ATTRIBUTE_NAME).equals(attr.getName()) || (mapper.getConfig().get(AttributeStatementHelper.FRIENDLY_NAME) != null && mapper.getConfig().get(AttributeStatementHelper.FRIENDLY_NAME).equals(attr.getFriendlyName()))).findAny().orElse(null);
						if (mapperModel != null) {
							ProtocolMapperRepresentation mapper = new ProtocolMapperRepresentation();
							mapper.setName(mapperModel != null ? mapperModel.getName() : (attr.getFriendlyName() != null ? attr.getFriendlyName() : attr.getName()));
							mapper.setProtocol("saml");
							mapper.setProtocolMapper(UserAttributeStatementMapper.PROVIDER_ID);
							Map<String, String> config = new HashMap<>();
							config.put(ProtocolMapperUtils.USER_ATTRIBUTE, mapperModel != null ? mapperModel.getConfig().get(ProtocolMapperUtils.USER_ATTRIBUTE) : (attr.getFriendlyName() != null ? attr.getFriendlyName() : attr.getName()));
							config.put(AttributeStatementHelper.SAML_ATTRIBUTE_NAME, attr.getName());
							if (attr.getFriendlyName() != null)
								config.put(AttributeStatementHelper.FRIENDLY_NAME, attr.getFriendlyName());
							if (attr.getNameFormat() != null)
								config.put(AttributeStatementHelper.SAML_ATTRIBUTE_NAMEFORMAT, EntityDescriptorDescriptionConverter.getSAMLNameFormat(attr.getNameFormat()));
							mapper.setConfig(config);
							mappers.add(mapper);
						}
					});
			app.setProtocolMappers(mappers);
		}

		Boolean signing = Boolean.FALSE;
		Boolean encrypt = Boolean.FALSE;
		for (KeyDescriptorType keyDescriptor : spDescriptorType.getKeyDescriptor()) {
			X509Certificate cert = null;
			try {
				cert = SAMLMetadataUtil.getCertificate(keyDescriptor);
			} catch (ConfigurationException e) {
				throw new RuntimeException(e);
			} catch (ProcessingException e) {
				throw new RuntimeException(e);
			}
			String certPem = KeycloakModelUtils.getPemFromCertificate(cert);
			if (keyDescriptor.getUse() == KeyTypes.SIGNING) {
				app.getAttributes().put(SamlConfigAttributes.SAML_SIGNING_CERTIFICATE_ATTRIBUTE, certPem);
			    signing = Boolean.TRUE;
			} else if (keyDescriptor.getUse() == KeyTypes.ENCRYPTION) {
				app.getAttributes().put(SamlConfigAttributes.SAML_ENCRYPTION_CERTIFICATE_ATTRIBUTE, certPem);
				encrypt = Boolean.TRUE;
			}
		}
		app.getAttributes().put(SamlConfigAttributes.SAML_ENCRYPT, encrypt.toString());
		app.getAttributes().put(SamlConfigAttributes.SAML_CLIENT_SIGNATURE_ATTRIBUTE, signing.toString());

		app.setRedirectUris(redirectUris);
	}

	private void updateClient(ClientRepresentation clientRep, ClientModel clientModel, SPSSODescriptorType spDescriptorType, OrganizationType organization, Date validUntil, String preferredLang){
		parseClient(clientRep, spDescriptorType, organization, validUntil, preferredLang);
		RepresentationToModel.updateClient(clientRep, clientModel);
		clientModel.getProtocolMappers().forEach(mapper -> clientModel.removeProtocolMapper(mapper));
		clientRep.getProtocolMappers().forEach(mapper -> clientModel.addProtocolMapper(RepresentationToModel.toModel(mapper)));

		ValidationUtil.validateClient(session, clientModel, null, true, r -> {
			session.getTransactionManager().setRollbackOnly();
			throw new RuntimeException("Invalid client " + clientRep.getClientId() + ": " + r.getAllErrorsAsString());
		});
	}

    private boolean parseEntity(EntityDescriptorType entity) {
        // whitelist for entityId and registrationAuthority
        // blacklist for entityId and registrationAuthority
        // whitelist is superior in all cases than blacklist
        String authority = null;
        if (entity.getExtensions()!= null && entity.getExtensions().getRegistrationInfo() != null) {
            authority = entity.getExtensions().getRegistrationInfo().getRegistrationAuthority().toString();
        }

        return model.getEntityIdAllowList().contains(entity.getEntityID())
            || (authority != null && model.getRegistrationAuthorityAllowList().contains(authority))
            || (model.getCategoryAllowList() != null && entity.getExtensions()!= null && entity.getExtensions().getEntityAttributes() != null
                && containsAttribute(model.getCategoryAllowList(), entity.getExtensions().getEntityAttributes().getAttribute()))
            || (model.getEntityIdAllowList().isEmpty() && model.getRegistrationAuthorityAllowList().isEmpty()
                && model.getCategoryAllowList().isEmpty()
                && (model.getEntityIdDenyList().isEmpty() || !model.getEntityIdDenyList().contains(entity.getEntityID()))
                && (model.getCategoryDenyList().isEmpty() || entity.getExtensions()== null ||entity.getExtensions().getEntityAttributes() == null
                    || !containsAttribute(model.getCategoryDenyList(),
                        entity.getExtensions().getEntityAttributes().getAttribute()))
                && (model.getRegistrationAuthorityDenyList().isEmpty()
                    || !model.getRegistrationAuthorityDenyList().contains(authority)));
    }

    private boolean containsAttribute(Map<String, List<String>> map, List<AttributeType> attributes) {
        return attributes.stream()
            .filter(attr -> map.containsKey(attr.getName()) && attr.getAttributeValue().size() == map.get(attr.getName()).size()
                && attr.getAttributeValue().stream().map(Object::toString).collect(Collectors.toList())
                    .containsAll(map.get(attr.getName())))
            .count() > 0;

    }

    private void parseIdP(IdentityProviderModel identityProviderModel, Date validUntil, EntityDescriptorType entity,
							 IDPSSODescriptorType idpDescriptor, String preferredLang) {
		identityProviderModel.setEnabled(validUntil!= null &&  validUntil.before(new Date()) ? false : true);
       	identityProviderModel.getConfig().put("entityId", entity.getEntityID());

        LocalizedNameType displayName = idpDescriptor.getExtensions() != null
            && idpDescriptor.getExtensions().getUIInfo() != null
                ? idpDescriptor.getExtensions().getUIInfo().getDisplayName().stream()
                    .filter(dn -> preferredLang.equals(dn.getLang())).findAny().orElse(null)
                : null;
        if (displayName != null) {
            identityProviderModel.setDisplayName(displayName.getValue());
        } else {
            displayName = entity.getOrganization().getOrganizationDisplayName().stream()
                .filter(dn -> preferredLang.equals(dn.getLang())).findAny()
                .orElse(entity.getOrganization().getOrganizationDisplayName().stream()
                    .filter(dn -> preferredLang.equals(dn.getLang())).findAny().orElse(null));
            if (displayName != null) {
                identityProviderModel.setDisplayName(displayName.getValue());
            } else {
                identityProviderModel.setDisplayName(entity.getEntityID());
            }

        }

        parseIDPSSODescriptorType(identityProviderModel, idpDescriptor);

        // check for hide on login attibute - for update if condition is false set value to false
        identityProviderModel.getConfig().put("hideOnLoginPage", "false");
        if (entity.getExtensions() != null && entity.getExtensions().getEntityAttributes() != null) {
            for (AttributeType attribute : entity.getExtensions().getEntityAttributes().getAttribute()) {
                if (GeneralConstants.MACEDIR.equals(attribute.getName())
                    && attribute.getAttributeValue().contains(GeneralConstants.HIDE_FOR_DISCOVERY)) {
                    identityProviderModel.getConfig().put("hideOnLoginPage", "true");
                }
            }

        }
    }

	private void parseIDPSSODescriptorType (IdentityProviderModel identityProviderModel, IDPSSODescriptorType idpDescriptor) {

		String singleSignOnServiceUrl = null;
		Boolean postBindingRequest = false;
		Boolean postBindingLogout = false;
		for (EndpointType endpoint : idpDescriptor.getSingleSignOnService()) {
			if (endpoint.getBinding().toString().equals(JBossSAMLURIConstants.SAML_HTTP_POST_BINDING.get())) {
				singleSignOnServiceUrl = endpoint.getLocation().toString();
				postBindingRequest = true;
			} else if (endpoint.getBinding().toString().equals(JBossSAMLURIConstants.SAML_HTTP_REDIRECT_BINDING.get())){
				singleSignOnServiceUrl = endpoint.getLocation().toString();
				postBindingRequest = false;
				break;
			}
		}
		String singleLogoutServiceUrl = null;
		for (EndpointType endpoint : idpDescriptor.getSingleLogoutService()) {
			if (endpoint.getBinding().toString().equals(JBossSAMLURIConstants.SAML_HTTP_POST_BINDING.get())) {
				singleLogoutServiceUrl = endpoint.getLocation().toString();
				postBindingLogout = true;
			} else if (endpoint.getBinding().toString().equals(JBossSAMLURIConstants.SAML_HTTP_REDIRECT_BINDING.get())){
				singleLogoutServiceUrl = endpoint.getLocation().toString();
				postBindingLogout = false;
				break;
			}
		}

		identityProviderModel.getConfig().put(SAMLIdentityProviderConfig.SINGLE_LOGOUT_SERVICE_URL, singleLogoutServiceUrl);
		identityProviderModel.getConfig().put(SAMLIdentityProviderConfig.SINGLE_SIGN_ON_SERVICE_URL, singleSignOnServiceUrl);
		identityProviderModel.getConfig().put(SAMLIdentityProviderConfig.WANT_AUTHN_REQUESTS_SIGNED, idpDescriptor.isWantAuthnRequestsSigned().toString());
		identityProviderModel.getConfig().put(SAMLIdentityProviderConfig.VALIDATE_SIGNATURE, idpDescriptor.isWantAuthnRequestsSigned().toString());
		identityProviderModel.getConfig().put(SAMLIdentityProviderConfig.POST_BINDING_AUTHN_REQUEST, postBindingRequest.toString());
		identityProviderModel.getConfig().put(SAMLIdentityProviderConfig.POST_BINDING_LOGOUT, postBindingLogout.toString());


		List<KeyDescriptorType> keyDescriptor = idpDescriptor.getKeyDescriptor();
		String defaultCertificate = null;

		if (keyDescriptor != null) {
			for (KeyDescriptorType keyDescriptorType : keyDescriptor) {
				Element keyInfo = keyDescriptorType.getKeyInfo();
				Element x509KeyInfo = DocumentUtil.getChildElement(keyInfo,
						new QName("dsig", "X509Certificate"));

				if (KeyTypes.SIGNING.equals(keyDescriptorType.getUse())) {
				    identityProviderModel.getConfig().put(SAMLIdentityProviderConfig.SIGNING_CERTIFICATE_KEY, x509KeyInfo.getTextContent());
				} else if (KeyTypes.ENCRYPTION.equals(keyDescriptorType.getUse())) {
				    identityProviderModel.getConfig().put(SAMLIdentityProviderConfig.ENCRYPTION_PUBLIC_KEY, x509KeyInfo.getTextContent());
				} else if (keyDescriptorType.getUse() == null) {
					defaultCertificate = x509KeyInfo.getTextContent();
				}
			}
		}

		if (defaultCertificate != null) {

			//array certificate
			if (identityProviderModel.getConfig().get(SAMLIdentityProviderConfig.SIGNING_CERTIFICATE_KEY)== null) {
			    identityProviderModel.getConfig().put(SAMLIdentityProviderConfig.SIGNING_CERTIFICATE_KEY, defaultCertificate);
			}

			if (identityProviderModel.getConfig().get(SAMLIdentityProviderConfig.ENCRYPTION_PUBLIC_KEY)== null) {
			    identityProviderModel.getConfig().put(SAMLIdentityProviderConfig.ENCRYPTION_PUBLIC_KEY, defaultCertificate);
			}
		}

		if ( model.getNameIDPolicyFormat() != null) {
			List<String> nameIdFormatList = idpDescriptor.getNameIDFormat();
			identityProviderModel.getConfig().put(SAMLIdentityProviderConfig.NAME_ID_POLICY_FORMAT,(nameIdFormatList != null && !nameIdFormatList.isEmpty()) ? nameIdFormatList.get(0) : model.getNameIDPolicyFormat());
		}

		if (identityProviderModel.getConfig().get(SAMLIdentityProviderConfig.MULTIPLE_PRINCIPALS) == null) {
			identityProviderModel.getConfig().put(SAMLIdentityProviderConfig.MULTIPLE_PRINCIPALS, model.getConfig().get(SAMLIdentityProviderConfig.MULTIPLE_PRINCIPALS));
		}

		//attribute consuming service index/name set federation only during creation
		if (model.getConfig().get(SAMLIdentityProviderConfig.ATTRIBUTE_CONSUMING_SERVICE_INDEX) != null) {
			identityProviderModel.getConfig().put(SAMLIdentityProviderConfig.ATTRIBUTE_CONSUMING_SERVICE_INDEX,  model.getConfig().get(SAMLIdentityProviderConfig.ATTRIBUTE_CONSUMING_SERVICE_INDEX));
		}
		if (model.getConfig().get(SAMLIdentityProviderConfig.ATTRIBUTE_CONSUMING_SERVICE_NAME) != null) {
			identityProviderModel.getConfig().put(SAMLIdentityProviderConfig.ATTRIBUTE_CONSUMING_SERVICE_NAME,  model.getConfig().get(SAMLIdentityProviderConfig.ATTRIBUTE_CONSUMING_SERVICE_NAME));
		}
	}


	@Override
	public void removeFederation() {

		logger.info("Removing federation " + model.getInternalId() + " and all its IdPs");

		//cancel federation update task
		TimerProvider timer = session.getProvider(TimerProvider.class);
		timer.cancelTask("UpdateFederation" + model.getInternalId());

		RealmModel realm = session.realms().getRealm(realmId);
		List<String> existingIdps = realm.getIdentityProvidersByFederation(model.getInternalId());
		existingIdps.stream().forEach(idpAlias -> realm.removeFederationIdp(model, idpAlias));

		realm.removeSAMLFederation(model.getInternalId());
	}


	@Override
	public void close() {
		// TODO Auto-generated method stub
	}




	public static String getHash(String str) {
		byte[] hashBytes;
		try {
			hashBytes = MessageDigest.getInstance("SHA-256").digest(str.getBytes());
		}
		catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
		StringBuilder sb = new StringBuilder();
        for(byte b : hashBytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
	}



	@Override
    public Response export(UriInfo uriInfo, RealmModel realm) {

        try {
            URI authnBinding = JBossSAMLURIConstants.SAML_HTTP_REDIRECT_BINDING.getUri();
			URI authnBindingLogout = JBossSAMLURIConstants.SAML_HTTP_REDIRECT_BINDING.getUri();

            if (model.isPostBindingResponse()) {
                authnBinding = JBossSAMLURIConstants.SAML_HTTP_POST_BINDING.getUri();
            }

			if (model.isPostBindingLogoutReceivingRequest()) {
				authnBindingLogout = JBossSAMLURIConstants.SAML_HTTP_POST_BINDING.getUri();
			}

            URI endpoint = uriInfo.getBaseUriBuilder().path("realms").path(realm.getName()).path("broker").path("endpoint")
                .build();

            boolean wantAuthnRequestsSigned = model.isWantAuthnRequestsSigned();
			boolean wantLogoutRequestsSigned = model.isWantLogoutRequestsSigned();
            boolean wantAssertionsSigned = model.isWantAssertionsSigned();
            boolean wantAssertionsEncrypted = model.isWantAssertionsEncrypted();
            String entityId = getEntityId(uriInfo, realm);
            String nameIDPolicyFormat = model.getNameIDPolicyFormat();

            List<Element> signingKeys = new LinkedList<>();
            List<Element> encryptionKeys = new LinkedList<>();

            session.keys().getKeysStream(realm, KeyUse.SIG, Algorithm.RS256).filter(Objects::nonNull)
                .filter(key -> key.getCertificate() != null).sorted(SamlService::compareKeys).forEach(key -> {
                    try {
                        Element element = SPMetadataDescriptor.buildKeyInfoElement(key.getKid(),
                            PemUtils.encodeCertificate(key.getCertificate()));
                        signingKeys.add(element);

                        if (key.getStatus() == KeyStatus.ACTIVE) {
                            encryptionKeys.add(element);
                        }
                    } catch (ParserConfigurationException e) {
                        logger.warn("Failed to export SAML SP Metadata!", e);
                        throw new RuntimeException(e);
                    }
                });

			// Prepare the metadata descriptor model
			StringWriter sw = new StringWriter();
			XMLStreamWriter writer = StaxUtil.getXMLStreamWriter(sw);
			SAMLMetadataWriter metadataWriter = new SAMLMetadataWriter(writer);

			EntityDescriptorType entityDescriptor = SPMetadataDescriptor.buildSPdescriptor(authnBinding, authnBindingLogout, endpoint, endpoint, wantAuthnRequestsSigned, wantLogoutRequestsSigned,
                wantAssertionsSigned, wantAssertionsEncrypted, entityId, nameIDPolicyFormat, signingKeys, encryptionKeys);

			// Create the AttributeConsumingService if at least one attribute importer mapper exists
			List<FederationMapperModel> mappers = model.getFederationMapperModels().stream().filter(mapper -> "saml-user-attribute-idp-mapper".equals(mapper.getIdentityProviderMapper())).collect(Collectors.toList());
			if (!mappers.isEmpty()) {
				int attributeConsumingServiceIndex = model.getConfig().get(SAMLIdentityProviderConfig.ATTRIBUTE_CONSUMING_SERVICE_INDEX) != null ?  Integer.parseInt(model.getConfig().get(SAMLIdentityProviderConfig.ATTRIBUTE_CONSUMING_SERVICE_INDEX)) : 1;
				String attributeConsumingServiceName = model.getConfig().get(SAMLIdentityProviderConfig.ATTRIBUTE_CONSUMING_SERVICE_NAME);
				//default value for attributeConsumingServiceName
				if (attributeConsumingServiceName == null)
					attributeConsumingServiceName = realm.getDisplayName() != null ? realm.getDisplayName() : realm.getName() ;
				AttributeConsumingServiceType attributeConsumingService = new AttributeConsumingServiceType(attributeConsumingServiceIndex);
				attributeConsumingService.setIsDefault(true);

				String currentLocale = realm.getDefaultLocale() == null ? "en" : realm.getDefaultLocale();
				LocalizedNameType attributeConsumingServiceNameElement = new LocalizedNameType(currentLocale);
				attributeConsumingServiceNameElement.setValue(attributeConsumingServiceName);
				attributeConsumingService.addServiceName(attributeConsumingServiceNameElement);

				// Look for the SP descriptor and add the attribute consuming service
				for (EntityDescriptorType.EDTChoiceType choiceType : entityDescriptor.getChoiceType()) {
					List<EntityDescriptorType.EDTDescriptorChoiceType> descriptors = choiceType.getDescriptors();
					for (EntityDescriptorType.EDTDescriptorChoiceType descriptor : descriptors) {
						descriptor.getSpDescriptor().addAttributeConsumerService(attributeConsumingService);
					}
				}

				// Add the attribute mappers
				mappers.forEach(mapper -> {
					SamlMetadataDescriptorUpdater metadataAttrProvider = (SamlMetadataDescriptorUpdater)  session.getKeycloakSessionFactory().getProviderFactory(IdentityProviderMapper.class, mapper.getIdentityProviderMapper());
					metadataAttrProvider.updateMetadata(new IdentityProviderMapperModel(mapper,null), entityDescriptor);
				});
			}

			// Write the metadata and export it to a string
			metadataWriter.writeEntityDescriptor(entityDescriptor);

			String descriptor = sw.toString();

			// Metadata signing
			if (model.getConfig().get(SAMLIdentityProviderConfig.SIGN_SP_METADATA) != null && Boolean.parseBoolean(model.getConfig().get(SAMLIdentityProviderConfig.SIGN_SP_METADATA)))
			{
				KeyManager.ActiveRsaKey activeKey = session.keys().getActiveRsaKey(realm);
				String keyName = XmlKeyInfoKeyNameTransformer.NONE.getKeyName(activeKey.getKid(), activeKey.getCertificate());
				KeyPair keyPair = new KeyPair(activeKey.getPublicKey(), activeKey.getPrivateKey());

				Document metadataDocument = DocumentUtil.getDocument(descriptor);
				SAML2Signature signatureHelper = new SAML2Signature();
				signatureHelper.setSignatureMethod(SignatureAlgorithm.RSA_SHA256.getXmlSignatureMethod());
				signatureHelper.setDigestMethod(SignatureAlgorithm.RSA_SHA256.getXmlSignatureDigestMethod());

				Node nextSibling = metadataDocument.getDocumentElement().getFirstChild();
				signatureHelper.setNextSibling(nextSibling);

				signatureHelper.signSAMLDocument(metadataDocument, keyName, keyPair, CanonicalizationMethod.EXCLUSIVE);

				descriptor = DocumentUtil.getDocumentAsString(metadataDocument);
			}
            return Response.ok(descriptor, MediaType.APPLICATION_XML_TYPE).build();
        } catch (Exception e) {
            logger.warn("Failed to export SAML SP Metadata!", e);
            throw new RuntimeException(e);
        }
    }

	private String getEntityId(UriInfo uriInfo, RealmModel realm) {
		String configEntityId = model.getConfig().get(SAMLIdentityProviderConfig.SP_ENTITY_ID);

		if (configEntityId == null || configEntityId.isEmpty())
			return UriBuilder.fromUri(uriInfo.getBaseUri()).path("realms").path(realm.getName()).build().toString();
		else
			return configEntityId;
	}

}
