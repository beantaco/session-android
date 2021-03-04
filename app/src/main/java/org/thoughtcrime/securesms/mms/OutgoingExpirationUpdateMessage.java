package org.thoughtcrime.securesms.mms;

import org.session.libsession.messaging.messages.control.ExpirationTimerUpdate;
import org.session.libsession.messaging.sending_receiving.attachments.Attachment;
import org.session.libsession.messaging.threads.DistributionTypes;
import org.session.libsession.messaging.threads.recipients.Recipient;

import java.util.Collections;
import java.util.LinkedList;

public class OutgoingExpirationUpdateMessage extends OutgoingSecureMediaMessage {

  public OutgoingExpirationUpdateMessage(Recipient recipient, long sentTimeMillis, long expiresIn) {
    super(recipient, "", new LinkedList<Attachment>(), sentTimeMillis,
          DistributionTypes.CONVERSATION, expiresIn, null, Collections.emptyList(),
          Collections.emptyList());
  }

  public static OutgoingExpirationUpdateMessage from(ExpirationTimerUpdate message,
                                          Recipient recipient) {
    return new OutgoingExpirationUpdateMessage(recipient, message.getSentTimestamp(), message.getDuration() * 1000);
  }

  @Override
  public boolean isExpirationUpdate() {
    return true;
  }

}
