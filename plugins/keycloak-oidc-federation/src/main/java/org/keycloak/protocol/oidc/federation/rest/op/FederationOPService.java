package org.keycloak.protocol.oidc.federation.rest.op;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.jboss.logging.Logger;
import org.keycloak.OAuth2Constants;
import org.keycloak.events.EventBuilder;
import org.keycloak.events.EventType;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ModelDuplicateException;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.utils.ModelToRepresentation;
import org.keycloak.models.utils.RepresentationToModel;
import org.keycloak.protocol.oidc.federation.beans.EntityStatement;
import org.keycloak.protocol.oidc.federation.beans.MetadataPolicy;
import org.keycloak.protocol.oidc.federation.beans.OIDCFederationClientRepresentation;
import org.keycloak.protocol.oidc.federation.beans.OIDCFederationClientRepresentationPolicy;
import org.keycloak.protocol.oidc.federation.beans.PolicyList;
import org.keycloak.protocol.oidc.federation.configuration.Config;
import org.keycloak.protocol.oidc.federation.exceptions.BadSigningOrEncryptionException;
import org.keycloak.protocol.oidc.federation.exceptions.UnparsableException;
import org.keycloak.protocol.oidc.federation.helpers.FedUtils;
import org.keycloak.protocol.oidc.federation.paths.TrustChain;
import org.keycloak.protocol.oidc.federation.processes.TrustChainProcessor;
import org.keycloak.protocol.oidc.mappers.AbstractPairwiseSubMapper;
import org.keycloak.protocol.oidc.mappers.PairwiseSubMapperHelper;
import org.keycloak.protocol.oidc.mappers.SHA256PairwiseSubMapper;
import org.keycloak.protocol.oidc.utils.OIDCResponseType;
import org.keycloak.protocol.oidc.utils.SubjectType;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;
import org.keycloak.representations.oidc.OIDCClientRepresentation;
import org.keycloak.services.ErrorResponseException;
import org.keycloak.services.ServicesLogger;
import org.keycloak.services.Urls;
import org.keycloak.services.clientregistration.ClientRegistrationAuth;
import org.keycloak.services.clientregistration.ClientRegistrationContext;
import org.keycloak.services.clientregistration.ClientRegistrationException;
import org.keycloak.services.clientregistration.ClientRegistrationProvider;
import org.keycloak.services.clientregistration.ClientRegistrationTokenUtils;
import org.keycloak.services.clientregistration.ErrorCodes;
import org.keycloak.services.clientregistration.oidc.DescriptionConverter;
import org.keycloak.services.clientregistration.oidc.OIDCClientRegistrationContext;
import org.keycloak.services.clientregistration.policy.RegistrationAuth;
import org.keycloak.services.managers.ClientManager;
import org.keycloak.services.managers.RealmManager;
import org.keycloak.services.validation.ValidationMessages;
import org.keycloak.urls.UrlType;
import org.keycloak.validation.ClientValidationUtil;

public class FederationOPService implements ClientRegistrationProvider {

    private static final Logger logger = Logger.getLogger(FederationOPService.class);

    private KeycloakSession session;
    private EventBuilder event;
    private ClientRegistrationAuth auth;
    private static final Set<String> ALLOWED_RESPONSE_TYPES = Stream
        .of(OIDCResponseType.CODE, OIDCResponseType.TOKEN, OIDCResponseType.ID_TOKEN, OIDCResponseType.NONE)
        .collect(Collectors.toSet());
    private TrustChainProcessor trustChainProcessor;

    public FederationOPService(KeycloakSession session) {
        this.session = session;
        EventBuilder event = new EventBuilder(session.getContext().getRealm(), session, session.getContext().getConnection());
        setEvent(event);
        //endpoint = oidc for being oidc client
        setAuth(new ClientRegistrationAuth(session, this, event, "oidc"));
        trustChainProcessor = new TrustChainProcessor(session);
    }

    
    /**
     * THIS SHOULD BE REMOVED
     */
    @GET
    @Path("trustchain")
    @Produces("application/json; charset=utf-8")
    public Response getTrustChain() throws IOException, UnparsableException, BadSigningOrEncryptionException {
      String leafNodeBaseUrl = "http://localhost:8081/auth/realms/master"; 
      Set<String> trustAnchorIds = Config.getConfig().getTrustAnchors().stream().collect(Collectors.toSet());
      TrustChainProcessor trustChainProcessor = new TrustChainProcessor(session);
      List<TrustChain> trustChain = trustChainProcessor.constructTrustChains(leafNodeBaseUrl, trustAnchorIds);
      return Response.ok(trustChain).build();
    }
    
    @POST
    @Path("fedreg")
    public Response getFederationRegistration(String jwtStatement) throws UnparsableException {

        EntityStatement statement;
        try {
            statement = TrustChainProcessor.parseAndValidateSelfSigned(jwtStatement);
        } catch (UnparsableException e) {
            e.printStackTrace();
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity("Exception in parsing entity statement").build();
        } catch (BadSigningOrEncryptionException e) {
            e.printStackTrace();
            return Response.status(Response.Status.UNAUTHORIZED).entity("No valid token").build();
        }

        if(!statement.getIssuer().trim().equals(statement.getSubject().trim())) {
            return Response.status(Response.Status.BAD_REQUEST).entity("The registration request issuer differs from the subject.").build();
        }

        Set<String> trustAnchorIds = Config.getConfig().getTrustAnchors().stream().collect(Collectors.toSet());

        logger.info("starting validating trust chains");
        ConcurrentHashMap<String, List<TrustChain>> authHintsTrustChains = new ConcurrentHashMap<String, List<TrustChain>>();

        statement.getAuthorityHints().parallelStream().forEach(authorityHint -> {
            try {
                List<TrustChain> trustChains = trustChainProcessor.constructTrustChains(authorityHint, trustAnchorIds);
                if(trustChains!=null && !trustChains.isEmpty()) {
                    authHintsTrustChains.put(authorityHint, trustChains);
                }
            } catch (IOException | UnparsableException | BadSigningOrEncryptionException e) {
                // TODO Replace with an appropriate log here
                e.printStackTrace();
            }
        });

        // 9.2.1.2.1. bullet 1 found and verified at least one trust chain
        if(authHintsTrustChains.size() > 0) {
            //just pick one randomly
            String authHintPicked = (String) authHintsTrustChains.keySet().toArray()[new Random()
                .nextInt(authHintsTrustChains.keySet().size())];
            List<TrustChain> chains = authHintsTrustChains.get(authHintPicked);
            TrustChain trustChainPicked = chains.get(new Random().nextInt(chains.size()));
            OIDCFederationClientRepresentationPolicy rpPolicy =createMetadataPolicies();
            ClientRepresentation clientSaved = createClient(statement.getMetadata().getRp(), statement.getIssuer());
            URI uri = session.getContext().getUri().getAbsolutePathBuilder().path(clientSaved.getClientId()).build();
            OIDCClientRepresentation clientOIDC = DescriptionConverter.toExternalResponse(session, clientSaved, uri);
            OIDCFederationClientRepresentation fedClient = new OIDCFederationClientRepresentation(clientOIDC,
                statement.getMetadata().getRp().getClient_registration_types(),
                statement.getMetadata().getRp().getOrganization_name());
            int idx = trustChainPicked.getChain().size() > 0 ? idx = trustChainPicked.getChain().size() - 1 : 0;
            String trustAnchorId = TrustChainProcessor.parse(trustChainPicked.getChain().get(idx)).getIssuer(); //this throws an exception. It should not continue if this fails.
            // add trust_anchor_id = trust anchor op chose
            fedClient.setTrust_anchor_id(trustAnchorId);
            
            statement.getMetadata().setRp(fedClient);
            // add trust_anchor_id = trust anchor op chose
            // add one or more authority_hints, from its collection
            MetadataPolicy policy = new MetadataPolicy(rpPolicy);
            statement.setMetadataPolicy(policy);
            statement.setJwks(FedUtils.getKeySet(session));
            statement.issuer(Urls.realmIssuer(session.getContext().getUri(UrlType.FRONTEND).getBaseUri(), session.getContext().getRealm().getName()));
            String token = session.tokens().encode(statement);
            return Response.ok(token).build();

        } else {
            return Response.status(Response.Status.FORBIDDEN).entity("Not accepted authority_hints").build();
        }


    }

    private OIDCFederationClientRepresentationPolicy createMetadataPolicies() {
        //enhancement may be needed from (OIDCConfigurationRepresentation) super.getConfig()
        PolicyList<String> policy = new PolicyList<String>();
        policy.setSubset_of(ALLOWED_RESPONSE_TYPES);
        OIDCFederationClientRepresentationPolicy rpPolicy = new OIDCFederationClientRepresentationPolicy();
        rpPolicy.setResponse_types(policy);
        return rpPolicy;

    }

    private ClientRepresentation createClient(OIDCFederationClientRepresentation clientRepresentastion, String identifier) {
        // 9.2.1.2.1. 3 check. How? -> extend client for having entity identifier??
        if (clientRepresentastion.getClientId() != null) {
            throw new ErrorResponseException(ErrorCodes.INVALID_CLIENT_METADATA, "Client Identifier included",
                Response.Status.BAD_REQUEST);
        }
        try {
            logger.info("Starting creating client for identifier: "+identifier);
            ClientRepresentation client = DescriptionConverter.toInternal(session, clientRepresentastion);
            client.setClientId(identifier);
            List<String> grantTypes = clientRepresentastion.getGrantTypes();

            if (grantTypes != null && grantTypes.contains(OAuth2Constants.UMA_GRANT_TYPE)) {
                client.setAuthorizationServicesEnabled(true);
            }

            OIDCClientRegistrationContext oidcContext = new OIDCClientRegistrationContext(session, client, this,
                clientRepresentastion);
            client = create(oidcContext);

            ClientModel clientModel = session.getContext().getRealm().getClientByClientId(client.getClientId());
            updatePairwiseSubMappers(clientModel, SubjectType.parse(clientRepresentastion.getSubjectType()),
                clientRepresentastion.getSectorIdentifierUri());
            return client;
        } catch (ClientRegistrationException cre) {
            ServicesLogger.LOGGER.clientRegistrationException(cre.getMessage());
            throw new ErrorResponseException(ErrorCodes.INVALID_CLIENT_METADATA, "Client metadata invalid",
                Response.Status.BAD_REQUEST);
        }
    }


    private ClientRepresentation create(ClientRegistrationContext context) {
        ClientRepresentation client = context.getClient();

        event.event(EventType.CLIENT_REGISTER);

        RegistrationAuth registrationAuth = RegistrationAuth.ANONYMOUS;

        ValidationMessages validationMessages = new ValidationMessages();
        if (!context.validateClient(validationMessages)) {
            String errorCode = validationMessages.fieldHasError("redirectUris") ? ErrorCodes.INVALID_REDIRECT_URI : ErrorCodes.INVALID_CLIENT_METADATA;
            throw new ErrorResponseException(
                    errorCode,
                    validationMessages.getStringMessages(),
                    Response.Status.BAD_REQUEST
            );
        }

        try {
            RealmModel realm = session.getContext().getRealm();
            ClientModel clientModel = ClientManager.createClient(session, realm, client, true);

            if (clientModel.isServiceAccountsEnabled()) {
                new ClientManager(new RealmManager(session)).enableServiceAccount(clientModel);
            }

            if (Boolean.TRUE.equals(client.getAuthorizationServicesEnabled())) {
                RepresentationToModel.createResourceServer(clientModel, session, true);
            }

            client = ModelToRepresentation.toRepresentation(clientModel, session);

            client.setSecret(clientModel.getSecret());

            ClientValidationUtil.validate(session, clientModel, true, c -> {
                session.getTransactionManager().setRollbackOnly();
                throw  new ErrorResponseException(ErrorCodes.INVALID_CLIENT_METADATA, c.getError(), Response.Status.BAD_REQUEST);
            });

            String registrationAccessToken = ClientRegistrationTokenUtils.updateRegistrationAccessToken(session, clientModel, registrationAuth);
            client.setRegistrationAccessToken(registrationAccessToken);

            event.client(client.getClientId()).success();
            return client;
        } catch (ModelDuplicateException e) {
            throw new ErrorResponseException(ErrorCodes.INVALID_CLIENT_METADATA, "Client Identifier in use", Response.Status.BAD_REQUEST);
        }
    }

    // same as in OIDCClientRegistrationProvider
    private void updatePairwiseSubMappers(ClientModel clientModel, SubjectType subjectType, String sectorIdentifierUri) {
        if (subjectType == SubjectType.PAIRWISE) {

            // See if we have existing pairwise mapper and update it. Otherwise create new
            AtomicBoolean foundPairwise = new AtomicBoolean(false);

            clientModel.getProtocolMappers().stream().filter((ProtocolMapperModel mapping) -> {
                if (mapping.getProtocolMapper().endsWith(AbstractPairwiseSubMapper.PROVIDER_ID_SUFFIX)) {
                    foundPairwise.set(true);
                    return true;
                } else {
                    return false;
                }
            }).forEach((ProtocolMapperModel mapping) -> {
                PairwiseSubMapperHelper.setSectorIdentifierUri(mapping, sectorIdentifierUri);
                clientModel.updateProtocolMapper(mapping);
            });

            // We don't have existing pairwise mapper. So create new
            if (!foundPairwise.get()) {
                ProtocolMapperRepresentation newPairwise = SHA256PairwiseSubMapper.createPairwiseMapper(sectorIdentifierUri,
                    null);
                clientModel.addProtocolMapper(RepresentationToModel.toModel(newPairwise));
            }

        } else {
            // Rather find and remove all pairwise mappers
            clientModel.getProtocolMappers().stream().filter((ProtocolMapperModel mapperRep) -> {
                return mapperRep.getProtocolMapper().endsWith(AbstractPairwiseSubMapper.PROVIDER_ID_SUFFIX);
            }).forEach((ProtocolMapperModel mapping) -> {
                clientModel.getProtocolMappers().remove(mapping);
            });
        }
    }

    @POST
    @Path("par")
    @Produces("text/plain; charset=utf-8")
    public String postPushedAuthorization() {
        String name = session.getContext().getRealm().getDisplayName();
        if (name == null) {
            name = session.getContext().getRealm().getName();
        }
        return "Hello " + name;
    }

    @Override
    public void setAuth(ClientRegistrationAuth auth) {
        this.auth = auth;
    }

    @Override
    public ClientRegistrationAuth getAuth() {
        return this.auth;
    }

    @Override
    public void setEvent(EventBuilder event) {
        this.event = event;
    }

    @Override
    public EventBuilder getEvent() {
        return event;
    }

    @Override
    public void close() {
    }

}