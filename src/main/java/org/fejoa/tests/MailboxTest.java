/*
 * Copyright 2014.
 * Distributed under the terms of the GPLv3 License.
 *
 * Authors:
 *      Clemens Zeidler <czei002@aucklanduni.ac.nz>
 */
package org.fejoa.tests;

import junit.framework.TestCase;
import org.fejoa.library.*;
import org.fejoa.library.crypto.*;
import org.fejoa.library.mailbox.*;

import java.io.IOException;
import java.security.KeyPair;
import java.util.List;


public class MailboxTest extends TestCase {
    ICryptoInterface crypto = Crypto.get();
    KeyId personalKeyId;
    ContactPrivate contactPrivate;
    ParcelCrypto parcelCrypto;
    CryptoSettings settings = CryptoSettings.getDefault();

    @Override
    public void setUp() throws Exception {
        super.setUp();

        KeyPair personalKey = crypto.generateKeyPair(settings.asymmetricKeySize);
        byte hashResult[] = CryptoHelper.sha1Hash(personalKey.getPublic().getEncoded());
        personalKeyId = new KeyId(CryptoHelper.toHex(hashResult));

        KeyStore.AsymmetricKeyData asymmetricKeyData = new KeyStore.AsymmetricKeyData(personalKey);
        contactPrivate = new ContactPrivate(null, null, personalKeyId,
                asymmetricKeyData);

        parcelCrypto = new ParcelCrypto(settings);
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
    }

    private IContactFinder getContactFinder() {
        return new IContactFinder() {
            @Override
            public Contact find(String keyId) {
                if (keyId.equals(personalKeyId.getKeyId()))
                    return contactPrivate;
                return null;
            }

            @Override
            public Contact findByAddress(String address) {
                return null;
            }
        };
    }
    public void testSignatureEnvelope() throws IOException, CryptoException {
        byte[] testData = "test data".getBytes();

        SignatureEnvelopeWriter writer = new SignatureEnvelopeWriter(contactPrivate, personalKeyId, settings, null);
        byte[] packed = writer.pack(testData);
        CryptoSettings returnSettings = CryptoSettings.empty();
        SignatureEnvelopeReader reader = new SignatureEnvelopeReader(getContactFinder(), returnSettings, null);
        byte[] result = reader.unpack(packed);

        assertEquals(new String(testData), new String(result));
        assertEquals(writer.getUid(), reader.getUid());
    }

    public void testSymEnvelope() throws CryptoException, IOException {
        byte[] testData = "test data".getBytes();

        SecureSymEnvelopeWriter writer = new SecureSymEnvelopeWriter(parcelCrypto, null);
        byte[] packed = writer.pack(testData);
        SecureSymEnvelopeReader reader = new SecureSymEnvelopeReader(parcelCrypto, null);
        byte[] result = reader.unpack(packed);

        assertEquals(new String(testData), new String(result));
    }

    public void testAsymEnvelope() throws CryptoException, IOException {
        byte[] testData = "test data".getBytes();

        SecureAsymEnvelopeWriter writer = new SecureAsymEnvelopeWriter(contactPrivate, personalKeyId, parcelCrypto,
                settings, null);
        byte[] packed = writer.pack(testData);
        CryptoSettings returnSettings = CryptoSettings.empty();
        SecureAsymEnvelopeReader reader = new SecureAsymEnvelopeReader(contactPrivate, returnSettings, null);
        byte[] result = reader.unpack(packed);

        assertEquals(new String(testData), new String(result));
    }

    class Participant {
        final public String address;
        final public String uid;

        public Participant(String address, String uid) {
            this.address = address;
            this.uid = uid;
        }
    }

    private boolean contains(List<MessageBranchInfo.Participant> list, Participant participant) {
        for (MessageBranchInfo.Participant current : list) {
            if (current.address.equals(participant.address) && current.uid.equals(participant.uid))
                return true;
        }
        return false;
    }

    public void testMessageBranchInfo() throws CryptoException, IOException {
        final String subject = "subject test";
        final Participant participant1 = new Participant("peter@non.com", "fakeUI1");
        final Participant participant2 = new Participant("otto@non.de", "fakeUI2");

        MessageBranchInfo branchInfo = new MessageBranchInfo();
        branchInfo.setSubject(subject);
        branchInfo.addParticipant(participant1.address, participant1.uid);
        branchInfo.addParticipant(participant2.address, participant2.uid);

        byte[] pack = branchInfo.write(parcelCrypto, contactPrivate, personalKeyId, settings);
        // load
        branchInfo = new MessageBranchInfo();
        branchInfo.load(parcelCrypto, getContactFinder(), pack);
        assertEquals(subject, branchInfo.getSubject());
        List<MessageBranchInfo.Participant> participantList =  branchInfo.getParticipants();
        assertTrue(contains(participantList, participant1));
        assertTrue(contains(participantList, participant2));
    }
}
