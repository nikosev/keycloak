package org.keycloak.saml.processing.core.parsers.saml.mdui;

import static org.keycloak.saml.processing.core.parsers.saml.metadata.SAMLMetadataQNames.ATTR_LANG;

import org.keycloak.dom.saml.v2.mdui.UIInfoType;
import org.keycloak.dom.saml.v2.metadata.LocalizedNameType;
import org.keycloak.dom.saml.v2.metadata.LocalizedURIType;
import org.keycloak.saml.common.exceptions.ParsingException;
import org.keycloak.saml.common.util.StaxParserUtil;
import org.keycloak.saml.processing.core.parsers.saml.metadata.AbstractStaxSamlMetadataParser;
import org.keycloak.saml.processing.core.parsers.saml.metadata.SAMLMetadataQNames;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.events.StartElement;
import java.net.URI;

public class SAMLUIInfoParser extends AbstractStaxSamlMetadataParser<UIInfoType> {

    private static final SAMLUIInfoParser INSTANCE = new SAMLUIInfoParser();

    private SAMLUIInfoParser() {
        super(SAMLMetadataQNames.UIINFO);
    }

    public static SAMLUIInfoParser getInstance() {
        return INSTANCE;
    }

    @Override
    protected UIInfoType instantiateElement(XMLEventReader xmlEventReader, StartElement element) throws ParsingException {
        return new UIInfoType();
    }

    @Override
    protected void processSubElement(XMLEventReader xmlEventReader, UIInfoType target, SAMLMetadataQNames element,
                                     StartElement elementDetail) throws ParsingException {
        switch (element) {
            case DISPLAY_NAME:
                LocalizedNameType displayName = new LocalizedNameType(
                        StaxParserUtil.getRequiredAttributeValue(elementDetail, ATTR_LANG));
                StaxParserUtil.advance(xmlEventReader);
                displayName.setValue(StaxParserUtil.getElementText(xmlEventReader));
                target.addDisplayName(displayName);
                break;
            case DESCRIPTION:
                LocalizedNameType description = new LocalizedNameType(
                        StaxParserUtil.getRequiredAttributeValue(elementDetail, ATTR_LANG));
                StaxParserUtil.advance(xmlEventReader);
                description.setValue(StaxParserUtil.getElementText(xmlEventReader));
                target.addDescription(description);
                break;
            case KEYWORDS:
                break;
            case INFORMATION_URL:
                LocalizedURIType informationURL = new LocalizedURIType(
                        StaxParserUtil.getRequiredAttributeValue(elementDetail, ATTR_LANG));
                StaxParserUtil.advance(xmlEventReader);
                informationURL.setValue(URI.create(StaxParserUtil.getElementText(xmlEventReader)));
                target.addInformationURL(informationURL);
                break;
            case PRIVACY_STATEMENT_URL:
                LocalizedURIType privacyStatementURL = new LocalizedURIType(
                        StaxParserUtil.getRequiredAttributeValue(elementDetail, ATTR_LANG));
                StaxParserUtil.advance(xmlEventReader);
                privacyStatementURL.setValue(URI.create(StaxParserUtil.getElementText(xmlEventReader)));
                target.addPrivacyStatementURL(privacyStatementURL);
                break;
            case LOGO:
                break;
            default:
                throw LOGGER.parserUnknownTag(StaxParserUtil.getElementName(elementDetail), elementDetail.getLocation());
        }
    }
}
