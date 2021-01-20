///*
// * Copyright 2016 Red Hat, Inc. and/or its affiliates
// * and other contributors as indicated by the @author tags.
// *
// * Licensed under the Apache License, Version 2.0 (the "License");
// * you may not use this file except in compliance with the License.
// * You may obtain a copy of the License at
// *
// * http://www.apache.org/licenses/LICENSE-2.0
// *
// * Unless required by applicable law or agreed to in writing, software
// * distributed under the License is distributed on an "AS IS" BASIS,
// * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// * See the License for the specific language governing permissions and
// * limitations under the License.
// */
//
//package org.keycloak.models.cache.infinispan.events;
//
//import java.util.Set;
//
//import org.keycloak.models.cache.infinispan.IdpCacheManager;
//import java.io.IOException;
//import java.io.ObjectInput;
//import java.io.ObjectOutput;
//import org.infinispan.commons.marshall.Externalizer;
//import org.infinispan.commons.marshall.MarshallUtil;
//import org.infinispan.commons.marshall.SerializeWith;
//
//
//@SerializeWith(IdentityProvidersRealmRemovedEvent.ExternalizerImpl.class)
//public class IdentityProvidersRealmRemovedEvent  extends InvalidationEvent implements IdentityProviderCacheInvalidationEvent {
//
//    private String realmId;
//
//    public static IdentityProvidersRealmRemovedEvent create(String realmId) {
//    	IdentityProvidersRealmRemovedEvent event = new IdentityProvidersRealmRemovedEvent();
//        event.realmId = realmId;
//        return event;
//    }
//
//    @Override
//    public String getId() {
//        return realmId; // Just a placeholder
//    }
//
//    @Override
//    public String toString() {
//        return String.format("IdentityProvidersRealmRemovedEvent [ realmId=%s ]", realmId);
//    }
//
//    @Override
//    public void addInvalidations(IdpCacheManager idpCache, Set<String> invalidations) {
//    	idpCache.invalidateRealmIdentityProviders(realmId, invalidations);
//    }
//
//    public static class ExternalizerImpl implements Externalizer<IdentityProvidersRealmRemovedEvent> {
//
//        private static final int VERSION_1 = 1;
//
//        @Override
//        public void writeObject(ObjectOutput output, IdentityProvidersRealmRemovedEvent obj) throws IOException {
//            output.writeByte(VERSION_1);
//
//            MarshallUtil.marshallString(obj.realmId, output);
//        }
//
//        @Override
//        public IdentityProvidersRealmRemovedEvent readObject(ObjectInput input) throws IOException, ClassNotFoundException {
//            switch (input.readByte()) {
//                case VERSION_1:
//                    return readObjectVersion1(input);
//                default:
//                    throw new IOException("Unknown version");
//            }
//        }
//
//        public IdentityProvidersRealmRemovedEvent readObjectVersion1(ObjectInput input) throws IOException, ClassNotFoundException {
//        	IdentityProvidersRealmRemovedEvent res = new IdentityProvidersRealmRemovedEvent();
//            res.realmId = MarshallUtil.unmarshallString(input);
//
//            return res;
//        }
//    }
//}