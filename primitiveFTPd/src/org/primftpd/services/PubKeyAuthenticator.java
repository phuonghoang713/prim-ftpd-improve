package org.primftpd.services;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator;
import org.apache.sshd.server.session.ServerSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.MessageDigest;
import java.security.PublicKey;
import java.util.List;

public class PubKeyAuthenticator implements PublickeyAuthenticator {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    private final List<PublicKey> pubKeys;

    public PubKeyAuthenticator(List<PublicKey> pubKeys) {
        this.pubKeys = pubKeys;
    }

    @Override
    public boolean authenticate(String username, PublicKey clientKey, ServerSession session) {
        if (clientKey == null) {
            return false;
        }
        logger.debug("attempting pub key auth, user: {}, client key class: '{}'",
                username, clientKey.getClass().getName());
        // never mind username
        for (PublicKey serverKey : pubKeys) {
            boolean keyEquals = keysEqual(serverKey, clientKey);
            logger.debug("pub key auth, success: {}, server key class '{}'",
                    keyEquals, serverKey != null ? serverKey.getClass().getName() : null);
            if (keyEquals) {
                return true;
            }
        }
        return false;
    }

    private boolean keysEqual(PublicKey serverKey, PublicKey clientKey) {
        if (serverKey == clientKey) {
            return true;
        }
        if (serverKey == null || clientKey == null) {
            return false;
        }
        // Keys of authorized_keys are created by this app, key of client by the ssh library: classes and
        // even names of algorithms may differ for the same key (e.g. 'Ed25519' and 'EdDSA').
        // The X.509 encoding is the same in any case.
        byte[] serverEncoded = serverKey.getEncoded();
        byte[] clientEncoded = clientKey.getEncoded();
        if (serverEncoded != null && clientEncoded != null && MessageDigest.isEqual(serverEncoded, clientEncoded)) {
            return true;
        }
        try {
            return KeyUtils.compareKeys(serverKey, clientKey);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
