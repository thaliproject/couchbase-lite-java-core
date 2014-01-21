package com.couchbase.lite.auth;

import com.couchbase.lite.CouchbaseLiteException;
import com.couchbase.lite.ReplicatorArguments;
import com.couchbase.lite.Status;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks through the authorizerFactories it has been given in the order they were given trying to find the first
 * one that matches the request params.
 */
public class AuthorizerFactoryManager {
    ArrayList<AuthorizerFactory> authorizers = new ArrayList<AuthorizerFactory>();

    public AuthorizerFactoryManager(List<AuthorizerFactory> authorizerFactories) {
        authorizers.addAll(authorizerFactories);
    }

    /**
     * The code checks to see if there is an auth section.
     * @param replicatorArguments
     * @return null if there is no authorizer needed otherwise the appropriate authorizer, if any
     * @throws CouchbaseLiteException
     */
    public Authorizer findAuthorizer(ReplicatorArguments replicatorArguments) throws CouchbaseLiteException {
        // Only the 'foreign' DB should have auth, not the local DB
        if ((replicatorArguments.getPush() && replicatorArguments.getSourceAuth() != null) ||
                (replicatorArguments.getPush() == false && replicatorArguments.getTargetAuth() != null)) {
            throw new CouchbaseLiteException("Auth eleement can only be used for foreign DB, not local DB.", new Status(Status.BAD_REQUEST));
        }

        // We only trigger off the auth section
        if (replicatorArguments.getTargetAuth() == null && replicatorArguments.getSourceAuth() == null) {
            return null;
        }

        for(AuthorizerFactory authorizerFactory : authorizers) {
            Authorizer authorizer = authorizerFactory.getAuthorizer(replicatorArguments);
            if (authorizer != null) {
                return authorizer;
            }
        }

        throw new CouchbaseLiteException("Auth element encodes unsupported auth request mechanism.", new Status(Status.BAD_REQUEST));
    }
}
