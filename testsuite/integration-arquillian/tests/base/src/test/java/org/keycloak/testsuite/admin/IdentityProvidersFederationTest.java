package org.keycloak.testsuite.admin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.ws.rs.NotFoundException;
import javax.ws.rs.core.Response;

import org.apache.tools.ant.filters.StringInputStream;
import org.jboss.logging.Logger;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.keycloak.admin.client.resource.IdentityProviderResource;
import org.keycloak.broker.saml.SAMLIdentityProviderConfig;
import org.keycloak.broker.saml.SAMLIdentityProviderConfig;
import org.keycloak.common.util.StreamUtil;
import org.keycloak.dom.saml.v2.metadata.AttributeConsumingServiceType;
import org.keycloak.dom.saml.v2.metadata.EntityDescriptorType;
import org.keycloak.dom.saml.v2.metadata.SPSSODescriptorType;
import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;
import org.keycloak.protocol.saml.SamlPrincipalType;
import org.keycloak.representations.idm.FederationMapperRepresentation;
import org.keycloak.representations.idm.IdentityProviderMapperRepresentation;
import org.keycloak.representations.idm.IdentityProviderRepresentation;
import org.keycloak.representations.idm.IdentityProvidersFederationRepresentation;
import org.keycloak.saml.common.constants.JBossSAMLURIConstants;
import org.keycloak.saml.common.exceptions.ParsingException;
import org.keycloak.saml.processing.core.parsers.saml.SAMLParser;
import org.keycloak.testsuite.Assert;
import org.keycloak.testsuite.util.AdminEventPaths;

import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import org.keycloak.util.JsonSerialization;

public class IdentityProvidersFederationTest extends AbstractAdminTest {

	private static Undertow SERVER;

	private static final Logger log = Logger.getLogger(IdentityProvidersFederationTest.class);

	private static final Set<String> aliasSet = new HashSet<>(
			Arrays.asList(new String[] { "6b6b716bef3c495083e31e1a71e8622e07d69b955cc3d9764fe28be5d0e8fb02",
					"00092d0295bee88b7b381b7c662cb0cc5919fe2d37b29896fa59923e107afda1","5168734e074c0bd8e432066851abed4a6b34f1d291b6ae8e8d0f163a71e48983" }));
	private static final String entityIdIdP = "https://idp.rash.al/simplesaml/saml2/idp/metadata.php";
	private static final String hashEntityIdIdP = "5168734e074c0bd8e432066851abed4a6b34f1d291b6ae8e8d0f163a71e48983" ;
	private static final String authority = "http://aai.grnet.gr/";
	private static final String attributeName="http://macedir.org/entity-category-support";
	private static final String attributeValue="http://clarin.eu/category/clarin-member";

	@BeforeClass
	public static void onBeforeClass() {
		SERVER = Undertow.builder().addHttpListener(8880, "localhost", new HttpHandler() {
			@Override
			public void handleRequest(HttpServerExchange exchange) throws Exception {
				writeResponse(exchange.getRequestURI(), exchange);
			}

			private void writeResponse(String file, HttpServerExchange exchange) throws IOException {
				exchange.getResponseSender().send(
						StreamUtil.readString(getClass().getResourceAsStream("/federation/saml" + file), Charset.defaultCharset()));
			}
		}).build();

		SERVER.start();
	}

	@AfterClass
	public static void onAfterClass() {
		if (SERVER != null) {
			SERVER.stop();
		}
	}


	@Test
    public void testCreateUpdateAndRemove() throws IOException {

        String internalId = createFederation("edugain-sample", "http://localhost:8880/edugain-sample-test.xml", new HashSet<>(),
            new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashMap<>());
        createMapper(internalId);

        sleep(70000);
        // first execute trigger update idps and then get identity providers federation
        IdentityProvidersFederationRepresentation representation = realm.identityProvidersFederation()
                .getIdentityProviderFederation(internalId);
        assertNotNull(representation);

        assertEquals("wrong federation alias", "edugain-sample", representation.getAlias());
        assertEquals("not saml federation", "saml", representation.getProviderId());
        assertEquals("wrong url", "http://localhost:8880/edugain-sample-test.xml",
                representation.getUrl());

        // must be three saml idps
        List<String> idps = realm.identityProviders().getIdPsPerFederation(representation.getInternalId());
        assertEquals(3, idps.size());
        idps.stream().forEach(idpAlias -> {
            assertTrue("wrong IdPs", aliasSet.contains(idpAlias));
            // find idp and check parameters
            IdentityProviderResource provider = realm.identityProviders().get(idpAlias);
            IdentityProviderRepresentation idp = provider.toRepresentation();
            assertEquals("not saml IdP", "saml", idp.getProviderId());
            assertNotNull("empty IdP config", idp.getConfig());
            assertTrue("IdP singleSignOnServiceUrl not exist", idp.getConfig().containsKey("singleSignOnServiceUrl"));
            assertTrue("IdP postBindingAuthnRequest not exist", idp.getConfig().containsKey("postBindingAuthnRequest"));
            assertTrue( Boolean.valueOf(idp.getConfig().get("postBindingAuthnRequest")));
            //change postBindingAuthnRequest to false
            idp.getConfig().put("postBindingAuthnRequest", "false");
            IdentityProviderResource identityProviderResource = realm.identityProviders().get(idp.getAlias());
            identityProviderResource.update(idp);
            idp = identityProviderResource.toRepresentation();
            assertFalse( Boolean.valueOf(idp.getConfig().get("postBindingAuthnRequest")));
            //check that its idp has one attribute importer mapper
            List<IdentityProviderMapperRepresentation> mappers = identityProviderResource.getMappers();
            assertEquals(1, mappers.size());
            IdentityProviderMapperRepresentation mapper = mappers.get(0);
            assertEquals("my_mapper", mapper.getName());
            assertEquals("saml-user-attribute-idp-mapper", mapper.getIdentityProviderMapper());
            assertEquals("givenname",mapper.getConfig().get("attribute.name"));
            assertEquals("firstname",mapper.getConfig().get("user.attribute"));
        });

        //update federation in order to update idps based on xml
        representation.setUpdateFrequencyInMins(1);
        Response response = realm.identityProvidersFederation().create(representation);
        sleep(90000);
         //postBindingResponse must be true again
        representation = realm.identityProvidersFederation()
            .getIdentityProviderFederation(internalId);
        assertNotNull(representation);
        idps.stream().forEach(idpAlias -> {
            IdentityProviderResource provider = realm.identityProviders().get(idpAlias);
            IdentityProviderRepresentation idp = provider.toRepresentation();
            assertTrue("IdP postBindingResponse not exist", idp.getConfig().containsKey("postBindingAuthnRequest"));
            assertTrue( Boolean.valueOf(idp.getConfig().get("postBindingAuthnRequest")));
        });

        removeFederation(representation.getInternalId());

    }

	@Test
	public void testCreateWithDenyListandRemove() throws IOException {
	    //blacklist entityIdIdP entity id and registrationAuthority="http://aai.grnet.gr/"

		//create with excluding one idp
		Set<String> entityIdDenyList = new HashSet<>();
		entityIdDenyList.add(entityIdIdP);
		Set<String> authorityDenyList = new HashSet<>();
		authorityDenyList.add(authority);
		String internalId = createFederation("edugain-sample",
				"http://localhost:8880/edugain-sample-test.xml",entityIdDenyList,new HashSet<>(),authorityDenyList,new HashSet<>(), new HashMap<>());

		sleep(90000);
		// first execute trigger update idps and then get identity providers federation
		IdentityProvidersFederationRepresentation representation = realm.identityProvidersFederation()
				.getIdentityProviderFederation(internalId);
		assertNotNull(representation);

		assertEquals("wrong federation alias", "edugain-sample", representation.getAlias());
		assertEquals("not saml federation", "saml", representation.getProviderId());
		assertEquals("wrong url", "http://localhost:8880/edugain-sample-test.xml",
				representation.getUrl());

		// must be one idp
        List<String> idps = realm.identityProviders().getIdPsPerFederation(representation.getInternalId());
        assertEquals(1, idps.size());
        idps.stream().forEach(idpAlias -> {
            assertEquals("wrong IdP", hashEntityIdIdP, idpAlias);
            // find idp and check parameters
            IdentityProviderResource provider = realm.identityProviders().get(idpAlias);
            IdentityProviderRepresentation idp = provider.toRepresentation();
            assertEquals("not saml IdP", "saml", idp.getProviderId());
            assertNotNull("empty IdP config", idp.getConfig());
            assertTrue("IdP singleSignOnServiceUrl not exist", idp.getConfig().containsKey("singleSignOnServiceUrl"));
            assertTrue("IdP postBindingAuthnRequest not exist", idp.getConfig().containsKey("postBindingAuthnRequest"));
            assertTrue(Boolean.valueOf(idp.getConfig().get("postBindingAuthnRequest")));
        });

		removeFederation(representation.getInternalId());
	}
	
	@Test
    public void testCreateWithAllowListandRemove() throws IOException {
	  //whitelist entityIdIdP entity id and registrationAuthority="http://aai.grnet.gr/"
	    
        //create with excluding one idp
        Set<String> entityIdAllowList = new HashSet<>();
        entityIdAllowList.add(entityIdIdP);
        Set<String> authorityAllowList = new HashSet<>();
        authorityAllowList.add(authority);
        String internalId = createFederation("edugain-sample", "http://localhost:8880/edugain-sample-test.xml", new HashSet<>(),
            entityIdAllowList, new HashSet<>(), authorityAllowList, new HashMap<>());

        sleep(90000);
        // first execute trigger update idps and then get identity providers federation
        IdentityProvidersFederationRepresentation representation = realm.identityProvidersFederation()
                .getIdentityProviderFederation(internalId);
        assertNotNull(representation);

        assertEquals("wrong federation alias", "edugain-sample", representation.getAlias());
        assertEquals("not saml federation", "saml", representation.getProviderId());
        assertEquals("wrong url", "http://localhost:8880/edugain-sample-test.xml",
                representation.getUrl());

        // must be two saml idps and not contain
        List<String> idps = realm.identityProviders().getIdPsPerFederation(representation.getInternalId());
        assertEquals(2, idps.size());
        assertFalse("wrong IdPs",idps.contains(hashEntityIdIdP));
        idps.stream().forEach(idpAlias -> {
            assertTrue("wrong IdPs", aliasSet.contains(idpAlias));
            // find idp and check parameters
            IdentityProviderResource provider = realm.identityProviders().get(idpAlias);
            IdentityProviderRepresentation idp = provider.toRepresentation();
            assertEquals("not saml IdP", "saml", idp.getProviderId());
            assertNotNull("empty IdP config", idp.getConfig());
            assertTrue("IdP singleSignOnServiceUrl not exist", idp.getConfig().containsKey("singleSignOnServiceUrl"));
            assertTrue("IdP postBindingAuthnRequest not exist", idp.getConfig().containsKey("postBindingAuthnRequest"));
            assertTrue(Boolean.valueOf(idp.getConfig().get("postBindingAuthnRequest")));
        });

        removeFederation(representation.getInternalId());
    }
	
	@Test
    public void testCreateWithCategoryDenyListandRemove() throws IOException {
      //blacklist with attributeName and attributeValue exclude only hashEntityIdIdP
        
        Map<String,List<String>> blackMap =  new HashMap<>();
        blackMap.put(attributeName, Stream.of(attributeValue).collect(Collectors.toList()));
        String internalId = createFederation("edugain-sample", "http://localhost:8880/edugain-sample-test.xml", new HashSet<>(),
            new HashSet<>(), new HashSet<>(), new HashSet<>(), blackMap);

        sleep(90000);
        // first execute trigger update idps and then get identity providers federation
        IdentityProvidersFederationRepresentation representation = realm.identityProvidersFederation()
                .getIdentityProviderFederation(internalId);
        assertNotNull(representation);

        assertEquals("wrong federation alias", "edugain-sample", representation.getAlias());
        assertEquals("not saml federation", "saml", representation.getProviderId());
        assertEquals("wrong url", "http://localhost:8880/edugain-sample-test.xml",
                representation.getUrl());

        // must be two saml idps and not contain
        List<String> idps = realm.identityProviders().getIdPsPerFederation(representation.getInternalId());
        assertEquals(2, idps.size());
        assertFalse("wrong IdPs", idps.contains(hashEntityIdIdP));
        idps.stream().forEach(idpAlias -> {
            assertTrue("wrong IdPs", aliasSet.contains(idpAlias));
            // find idp and check parameters
            IdentityProviderResource provider = realm.identityProviders().get(idpAlias);
            IdentityProviderRepresentation idp = provider.toRepresentation();
            assertEquals("not saml IdP", "saml", idp.getProviderId());
            assertNotNull("empty IdP config", idp.getConfig());
            assertTrue("IdP singleSignOnServiceUrl not exist", idp.getConfig().containsKey("singleSignOnServiceUrl"));
            assertTrue("IdP postBindingAuthnRequest not exist", idp.getConfig().containsKey("postBindingAuthnRequest"));
            assertTrue(Boolean.valueOf(idp.getConfig().get("postBindingAuthnRequest")));
        });

        removeFederation(representation.getInternalId());
    }

    @Test
    public void testCreateAndExportWithoutMappers() throws IOException, ParsingException {

        String internalId = createFederation("edugain-sample", "http://localhost:8880/edugain-sample-test.xml", new HashSet<>(),
                new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashMap<>());

        IdentityProvidersFederationRepresentation representation = realm.identityProvidersFederation()
                .getIdentityProviderFederation(internalId);
        Assert.assertNotNull(representation);
        String spDescriptorString = realm.identityProvidersFederation().export(representation.getAlias()).readEntity(String.class);
        SAMLParser parser = SAMLParser.getInstance();
        EntityDescriptorType o = (EntityDescriptorType) parser.parse(new StringInputStream(spDescriptorString));
        SPSSODescriptorType spDescriptor = o.getChoiceType().get(0).getDescriptors().get(0).getSpDescriptor();

        //attribute mappers do  exists-  AttributeConsumingService exist
        Assert.assertEquals(spDescriptor.getAssertionConsumerService().size(),1);
        Assert.assertEquals(spDescriptor.getSingleLogoutService().size(),1);
        Assert.assertEquals(spDescriptor.getNameIDFormat().size(),1);
        Assert.assertEquals(spDescriptor.getNameIDFormat().get(0),"urn:oasis:names:tc:SAML:2.0:nameid-format:persistent");
        Assert.assertTrue(spDescriptor.getAttributeConsumingService().isEmpty());

        removeFederation(internalId);

    }

    @Test
    public void testCreateAndExportWithMappers() throws IOException, ParsingException {

        String internalId = createFederation("edugain-sample", "http://localhost:8880/edugain-sample-test.xml", new HashSet<>(),
                new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashMap<>());
        createMapper(internalId);

        IdentityProvidersFederationRepresentation representation = realm.identityProvidersFederation()
                .getIdentityProviderFederation(internalId);
        Assert.assertNotNull(representation);
        String spDescriptorString = realm.identityProvidersFederation().export(representation.getAlias()).readEntity(String.class);
        SAMLParser parser = SAMLParser.getInstance();
        EntityDescriptorType o = (EntityDescriptorType) parser.parse(new StringInputStream(spDescriptorString));
        SPSSODescriptorType spDescriptor = o.getChoiceType().get(0).getDescriptors().get(0).getSpDescriptor();

        //attribute mappers do not exist- no AttributeConsumingService
        Assert.assertEquals(spDescriptor.getAssertionConsumerService().size(),1);
        Assert.assertEquals(spDescriptor.getSingleLogoutService().size(),1);
        Assert.assertEquals(spDescriptor.getNameIDFormat().size(),1);
        Assert.assertEquals(spDescriptor.getNameIDFormat().get(0),"urn:oasis:names:tc:SAML:2.0:nameid-format:persistent");
        Assert.assertFalse(spDescriptor.getAttributeConsumingService().isEmpty());
        AttributeConsumingServiceType attributeConsuming = spDescriptor.getAttributeConsumingService().get(0);
        Assert.assertEquals(attributeConsuming.getIndex(), 3);
        Assert.assertFalse(attributeConsuming.getRequestedAttribute().isEmpty());
        Assert.assertEquals(attributeConsuming.getRequestedAttribute().get(0).getName(), "givenname");
        Assert.assertEquals(attributeConsuming.getRequestedAttribute().get(0).getFriendlyName(), "given name");
        Assert.assertEquals(attributeConsuming.getRequestedAttribute().get(0).getNameFormat(), JBossSAMLURIConstants.ATTRIBUTE_FORMAT_URI.get());
        Assert.assertTrue(attributeConsuming.getServiceName() != null);
        Assert.assertEquals(attributeConsuming.getServiceName().get(0).getValue(), "federation");

        removeFederation(internalId);

    }

    @Test
    public void testFederationMappers() throws IOException {

        String internalId = createFederation("edugain-sample", "http://localhost:8880/edugain-sample-test.xml", new HashSet<>(),
                new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashMap<>());
        String mapperId = createMapper(internalId);
        FederationMapperRepresentation mapper = realm.identityProvidersFederation().getIdentityProviderFederationMapper(internalId,mapperId);
        assertEquals("my_mapper", mapper.getName());
        assertEquals("saml-user-attribute-idp-mapper", mapper.getIdentityProviderMapper());
        assertEquals("givenname",mapper.getConfig().get("attribute.name"));
        assertEquals("firstname",mapper.getConfig().get("user.attribute"));

        //update and remove mapper
        mapper.getConfig().put("user.attribute","name");
        realm.identityProvidersFederation().updateMapper(internalId,mapperId,mapper);
        mapper = realm.identityProvidersFederation().getIdentityProviderFederationMapper(internalId,mapperId);
        assertEquals("my_mapper", mapper.getName());
        assertEquals("saml-user-attribute-idp-mapper", mapper.getIdentityProviderMapper());
        assertEquals("givenname",mapper.getConfig().get("attribute.name"));
        assertEquals("name",mapper.getConfig().get("user.attribute"));

        realm.identityProvidersFederation().deleteMapper(internalId,mapperId);
        try {
            realm.identityProvidersFederation().getIdentityProviderFederationMapper(internalId,mapperId);
            Assert.fail("Not expected to found federation");

        } catch (NotFoundException nfe) {
            // Expected
        }
        removeFederation(internalId);
    }

    @Test
    public void testFederationMappersActions() throws IOException {

        String internalId = createFederation("edugain-sample", "http://localhost:8880/edugain-sample-test.xml", new HashSet<>(),
                new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashMap<>());

        sleep(90000);
        // first execute trigger update idps and then get identity providers federation
        IdentityProvidersFederationRepresentation representation = realm.identityProvidersFederation()
                .getIdentityProviderFederation(internalId);
        assertNotNull(representation);

        assertEquals("wrong federation alias", "edugain-sample", representation.getAlias());
        assertEquals("not saml federation", "saml", representation.getProviderId());
        assertEquals("wrong url", "http://localhost:8880/edugain-sample-test.xml",
                representation.getUrl());

        // must be three saml idps
        List<String> idps = realm.identityProviders().getIdPsPerFederation(representation.getInternalId());
        assertEquals(3, idps.size());
        idps.stream().forEach(idpAlias -> {
            assertTrue("wrong IdPs", aliasSet.contains(idpAlias));
            // find idp and check parameters
            IdentityProviderResource provider = realm.identityProviders().get(idpAlias);
            IdentityProviderRepresentation idp = provider.toRepresentation();
            assertEquals("not saml IdP", "saml", idp.getProviderId());
            assertNotNull("empty IdP config", idp.getConfig());
        });

        //add mapper to Idp
        String mapperId = createMapper(internalId);
        realm.identityProvidersFederation().massIdPMapperAction(internalId,mapperId,"add");

        idps.stream().forEach(idpAlias -> {
            // find idp and check parameters
            IdentityProviderResource provider = realm.identityProviders().get(idpAlias);
            IdentityProviderRepresentation idp = provider.toRepresentation();
            assertEquals("not saml IdP", "saml", idp.getProviderId());
            assertNotNull("empty IdP config", idp.getConfig());
            IdentityProviderResource identityProviderResource = realm.identityProviders().get(idp.getAlias());
            //check mapper
            List<IdentityProviderMapperRepresentation> mappers = identityProviderResource.getMappers();
            assertEquals(1, mappers.size());
            IdentityProviderMapperRepresentation mapper = mappers.get(0);
            assertEquals("my_mapper", mapper.getName());
            assertEquals("saml-user-attribute-idp-mapper", mapper.getIdentityProviderMapper());
            assertEquals("givenname",mapper.getConfig().get("attribute.name"));
            assertEquals("firstname",mapper.getConfig().get("user.attribute"));
        });

        //update mapper to Idp
        FederationMapperRepresentation fedMapper = realm.identityProvidersFederation().getIdentityProviderFederationMapper(internalId,mapperId);
        fedMapper.getConfig().put("user.attribute","name");
        realm.identityProvidersFederation().updateMapper(internalId,mapperId,fedMapper);
        realm.identityProvidersFederation().massIdPMapperAction(internalId,mapperId,"update");
        idps.stream().forEach(idpAlias -> {
            // find idp and check parameters
            IdentityProviderResource provider = realm.identityProviders().get(idpAlias);
            IdentityProviderRepresentation idp = provider.toRepresentation();
            assertEquals("not saml IdP", "saml", idp.getProviderId());
            assertNotNull("empty IdP config", idp.getConfig());
            IdentityProviderResource identityProviderResource = realm.identityProviders().get(idp.getAlias());
            //check mapper
            List<IdentityProviderMapperRepresentation> mappers = identityProviderResource.getMappers();
            assertEquals(1, mappers.size());
            IdentityProviderMapperRepresentation mapper = mappers.get(0);
            assertEquals("my_mapper", mapper.getName());
            assertEquals("saml-user-attribute-idp-mapper", mapper.getIdentityProviderMapper());
            assertEquals("givenname",mapper.getConfig().get("attribute.name"));
            assertEquals("name",mapper.getConfig().get("user.attribute"));
        });

        //remove mapper from Idp
        realm.identityProvidersFederation().massIdPMapperAction(internalId,mapperId,"remove");
        idps.stream().forEach(idpAlias -> {
            // find idp and check parameters
            IdentityProviderResource provider = realm.identityProviders().get(idpAlias);
            IdentityProviderRepresentation idp = provider.toRepresentation();
            assertEquals("not saml IdP", "saml", idp.getProviderId());
            assertNotNull("empty IdP config", idp.getConfig());
            IdentityProviderResource identityProviderResource = realm.identityProviders().get(idp.getAlias());
            //check mapper
            List<IdentityProviderMapperRepresentation> mappers = identityProviderResource.getMappers();
            assertEquals(0, mappers.size());
        });

        removeFederation(representation.getInternalId());

    }



    private String createFederation(String alias, String url, Set<String> denyList, Set<String> whitelist,
        Set<String> registrationAuthorityDenyList, Set<String> registrationAuthorityWhitelist, Map<String,List<String>> categoryDenyList) throws IOException {
        IdentityProvidersFederationRepresentation representation = new IdentityProvidersFederationRepresentation();
        representation.setAlias(alias);
        representation.setProviderId("saml");
        representation.setUpdateFrequencyInMins(60);
        representation.setUrl(url);
        representation.setEntityIdDenyList(denyList);
        representation.setEntityIdAllowList(whitelist);
        representation.setRegistrationAuthorityDenyList(registrationAuthorityDenyList);
        representation.setRegistrationAuthorityAllowList(registrationAuthorityWhitelist);
        representation.setCategoryDenyList(categoryDenyList);

        Map<String,String> config = new HashMap<>();
        config.put("nameIDPolicyFormat","urn:oasis:names:tc:SAML:2.0:nameid-format:persistent");
        LinkedList<SAMLIdentityProviderConfig.Principal> principals = new LinkedList<>();
        SAMLIdentityProviderConfig.Principal pr = new SAMLIdentityProviderConfig.Principal();
        pr.setPrincipalType(SamlPrincipalType.SUBJECT);
        pr.setNameIDPolicyFormat(JBossSAMLURIConstants.NAMEID_FORMAT_EMAIL.get());
        principals.add(pr);
        SAMLIdentityProviderConfig.Principal pr2 = new SAMLIdentityProviderConfig.Principal();
        pr2.setPrincipalType(SamlPrincipalType.SUBJECT);
        pr2.setNameIDPolicyFormat(JBossSAMLURIConstants.NAMEID_FORMAT_PERSISTENT.get());
        principals.add(pr2);
        SAMLIdentityProviderConfig.Principal pr3 = new SAMLIdentityProviderConfig.Principal();
        pr3.setPrincipalType(SamlPrincipalType.ATTRIBUTE);
        pr3.setPrincipalAttribute("subject-id");
        principals.add(pr3);
        config.put(SAMLIdentityProviderConfig.MULTIPLE_PRINCIPALS, JsonSerialization.writeValueAsString(principals));
        config.put("wantAssertionsEncrypted","true");
        config.put("wantAssertionsSigned","true");
        config.put("postBindingResponse","true");
        config.put("postBindingLogoutReceivingRequest","true");
        config.put("attributeConsumingServiceIndex","3");
        config.put("attributeConsumingServiceName","federation");
        representation.setConfig(config);

        Response response = realm.identityProvidersFederation().create(representation);
        String id = ApiUtil.getCreatedId(response);
        Assert.assertNotNull(id);
        response.close();

        // may not need all this implementation
        getCleanup().addIdentityProviderFederationId(id);

        assertAdminEvents.assertEvent(realmId, OperationType.CREATE, AdminEventPaths.identityProvidersFederationPath(alias),
            representation, ResourceType.IDENTITY_PROVIDERS_FEDERATION);

        return id;
    }

	private void removeFederation(String id) {
		realm.identityProvidersFederation().delete(id);

		// federation and its idps must be deleted
		try {
			realm.identityProvidersFederation().getIdentityProviderFederation(id);
			Assert.fail("Not expected to found federation");

		} catch (NotFoundException nfe) {
			// Expected
		}

		aliasSet.stream().forEach(idp -> {
			try {
				IdentityProviderResource resource = realm.identityProviders().get(idp);
				IdentityProviderRepresentation idpRes = resource.toRepresentation();
				Assert.fail("Not expected to found idp");

			} catch (NotFoundException nfe) {
				// Expected
			}
		});

	}

	private String createMapper(String federatedId) {
        FederationMapperRepresentation mapper = new FederationMapperRepresentation();
        mapper.setName("my_mapper");
        mapper.setIdentityProviderMapper("saml-user-attribute-idp-mapper");
        Map<String,String> config = new HashMap<>();
        config.put("attribute.name","givenname");
        config.put("attribute.friendly.name","given name");
        config.put("user.attribute","firstname");
        config.put("attribute.name.format",JBossSAMLURIConstants.ATTRIBUTE_FORMAT_URI.name());
        mapper.setConfig(config);

        Response response = realm.identityProvidersFederation().createMapper(federatedId,mapper);
        String id = ApiUtil.getCreatedId(response);
        Assert.assertNotNull(id);
        response.close();
        return id;
    }

	private static void sleep(long ms) {
		try {
			log.infof("Sleeping for %d ms", ms);
			Thread.sleep(ms);
		} catch (InterruptedException ie) {
			throw new RuntimeException(ie);
		}
	}

}
