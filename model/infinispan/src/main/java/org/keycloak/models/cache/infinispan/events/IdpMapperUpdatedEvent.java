package org.keycloak.models.cache.infinispan.events;

import org.infinispan.commons.marshall.Externalizer;
import org.infinispan.commons.marshall.MarshallUtil;
import org.infinispan.commons.marshall.SerializeWith;
import org.keycloak.cluster.ClusterEvent;
import org.keycloak.models.IdentityProviderMapperModel;
import org.keycloak.util.JsonSerialization;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

@SerializeWith(IdpMapperUpdatedEvent.ExternalizerImpl.class)
public class IdpMapperUpdatedEvent implements ClusterEvent {
    public static String EVENT_NAME = "IDP_MAPPER_UPDATED_EVENT";

    private String realmId;
    private IdentityProviderMapperModel identityProviderMapper;

    public IdpMapperUpdatedEvent(){ }

    public IdpMapperUpdatedEvent(String realmId, IdentityProviderMapperModel identityProviderMapper) {
        this.realmId = realmId;
        this.identityProviderMapper = identityProviderMapper;
    }

    public String getRealmId() {
        return realmId;
    }

    public void setRealmId(String realmId) {
        this.realmId = realmId;
    }

    public IdentityProviderMapperModel getIdentityProviderMapper() {
        return identityProviderMapper;
    }

    public void setIdentityProviderMapper(IdentityProviderMapperModel identityProviderMapper) {
        this.identityProviderMapper = identityProviderMapper;
    }

    public static class ExternalizerImpl implements Externalizer<IdpMapperRemovedEvent> {

        @Override
        public void writeObject(ObjectOutput output, IdpMapperRemovedEvent obj) throws IOException {
            MarshallUtil.marshallByteArray(JsonSerialization.writeValueAsBytes(obj), output);
        }

        @Override
        public IdpMapperRemovedEvent readObject(ObjectInput input) throws IOException, ClassNotFoundException {
            return JsonSerialization.readValue(MarshallUtil.unmarshallByteArray(input), IdpMapperRemovedEvent.class);
        }
    }

}