package org.thoughtcrime.securesms.jobs;

import android.content.Context;
import android.graphics.Bitmap;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.text.TextUtils;

import com.annimon.stream.Stream;

import org.session.libsession.messaging.sending_receiving.attachments.Attachment;
import org.session.libsession.messaging.sending_receiving.sharecontacts.Contact;
import org.session.libsession.utilities.MediaTypes;
import org.session.libsignal.utilities.Base64;
import org.session.libsession.utilities.Util;

import org.greenrobot.eventbus.EventBus;
import org.thoughtcrime.securesms.ApplicationContext;
import org.thoughtcrime.securesms.contactshare.ContactModelMapper;
import org.session.libsession.utilities.preferences.ProfileKeyUtil;
import org.session.libsession.messaging.threads.Address;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.events.PartProgressEvent;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.session.libsignal.utilities.logging.Log;
import org.thoughtcrime.securesms.mms.DecryptableStreamUriLoader;
import org.thoughtcrime.securesms.mms.OutgoingMediaMessage;
import org.thoughtcrime.securesms.mms.PartAuthority;
import org.session.libsession.messaging.threads.recipients.Recipient;
import org.thoughtcrime.securesms.util.BitmapDecodingException;
import org.thoughtcrime.securesms.util.BitmapUtil;
import org.session.libsignal.utilities.Hex;
import org.thoughtcrime.securesms.util.MediaUtil;
import org.session.libsignal.libsignal.util.guava.Optional;
import org.session.libsignal.service.api.messages.SignalServiceAttachment;
import org.session.libsignal.service.api.messages.SignalServiceAttachmentPointer;
import org.session.libsignal.service.api.messages.SignalServiceDataMessage;
import org.session.libsignal.service.api.messages.SignalServiceDataMessage.Preview;
import org.session.libsignal.service.api.messages.shared.SharedContact;
import org.session.libsignal.service.api.push.SignalServiceAddress;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public abstract class PushSendJob extends SendJob {

  private static final String TAG                           = PushSendJob.class.getSimpleName();

  protected PushSendJob(Job.Parameters parameters) {
    super(parameters);
  }

  protected static Job.Parameters constructParameters(Address destination) {
    return new Parameters.Builder()
                         .setQueue(destination.serialize())
                         .addConstraint(NetworkConstraint.KEY)
                         .setLifespan(TimeUnit.DAYS.toMillis(1))
                         .setMaxAttempts(1)
                         .build();
  }

  @Override
  protected final void onSend() throws Exception {
    onPushSend();
  }

  @Override
  public void onRetry() {
    super.onRetry();
    Log.i(TAG, "onRetry()");
  }

  protected Optional<byte[]> getProfileKey(@NonNull Recipient recipient) {
    if (!recipient.resolve().isSystemContact() && !recipient.resolve().isProfileSharing()) {
      return Optional.absent();
    }

    return Optional.of(ProfileKeyUtil.getProfileKey(context));
  }

  protected SignalServiceAddress getPushAddress(Address address) {
    String relay = null;
    return new SignalServiceAddress(address.toString(), Optional.fromNullable(relay));
  }

  protected SignalServiceAttachment getAttachmentFor(Attachment attachment) {
    try {
      if (attachment.getDataUri() == null || attachment.getSize() == 0) throw new IOException("Assertion failed, outgoing attachment has no data!");
      InputStream is = PartAuthority.getAttachmentStream(context, attachment.getDataUri());
      return SignalServiceAttachment.newStreamBuilder()
                                    .withStream(is)
                                    .withContentType(attachment.getContentType())
                                    .withLength(attachment.getSize())
                                    .withFileName(attachment.getFileName())
                                    .withVoiceNote(attachment.isVoiceNote())
                                    .withWidth(attachment.getWidth())
                                    .withHeight(attachment.getHeight())
                                    .withCaption(attachment.getCaption())
                                    .withListener((total, progress) -> EventBus.getDefault().postSticky(new PartProgressEvent(attachment, total, progress)))
                                    .build();
    } catch (IOException ioe) {
      Log.w(TAG, "Couldn't open attachment", ioe);
    }
    return null;
  }

  protected @NonNull List<SignalServiceAttachment> getAttachmentPointersFor(List<Attachment> attachments) {
    return Stream.of(attachments).map(this::getAttachmentPointerFor).filter(a -> a != null).toList();
  }

  protected @Nullable SignalServiceAttachment getAttachmentPointerFor(Attachment attachment) {
    if (TextUtils.isEmpty(attachment.getLocation())) {
      Log.w(TAG, "empty content id");
      return null;
    }

    if (TextUtils.isEmpty(attachment.getKey())) {
      Log.w(TAG, "empty encrypted key");
      return null;
    }

    try {
      long   id  = Long.parseLong(attachment.getLocation());
      byte[] key = Base64.decode(attachment.getKey());

      return new SignalServiceAttachmentPointer(id,
                                                attachment.getContentType(),
                                                key,
                                                Optional.of(Util.toIntExact(attachment.getSize())),
                                                Optional.absent(),
                                                attachment.getWidth(),
                                                attachment.getHeight(),
                                                Optional.fromNullable(attachment.getDigest()),
                                                Optional.fromNullable(attachment.getFileName()),
                                                attachment.isVoiceNote(),
                                                Optional.fromNullable(attachment.getCaption()), attachment.getUrl());
    } catch (IOException | ArithmeticException e) {
      Log.w(TAG, e);
      return null;
    }
  }

  protected static void notifyMediaMessageDeliveryFailed(Context context, long messageId) {
    long      threadId  = DatabaseFactory.getMmsDatabase(context).getThreadIdForMessage(messageId);
    Recipient recipient = DatabaseFactory.getThreadDatabase(context).getRecipientForThreadId(threadId);

    if (threadId != -1 && recipient != null) {
      ApplicationContext.getInstance(context).messageNotifier.notifyMessageDeliveryFailed(context, recipient, threadId);
    }
  }

  protected Optional<SignalServiceDataMessage.Quote> getQuoteFor(OutgoingMediaMessage message) {
    if (message.getOutgoingQuote() == null) return Optional.absent();

    long                                                  quoteId          = message.getOutgoingQuote().getId();
    String                                                quoteBody        = message.getOutgoingQuote().getText();
    Address                                               quoteAuthor      = message.getOutgoingQuote().getAuthor();
    List<SignalServiceDataMessage.Quote.QuotedAttachment> quoteAttachments = new LinkedList<>();

    for (Attachment attachment : message.getOutgoingQuote().getAttachments()) {
      BitmapUtil.ScaleResult  thumbnailData = null;
      SignalServiceAttachment thumbnail     = null;
      String                  thumbnailType = MediaTypes.IMAGE_JPEG;

      try {
        if (MediaUtil.isImageType(attachment.getContentType()) && attachment.getDataUri() != null) {
          Bitmap.CompressFormat format = BitmapUtil.getCompressFormatForContentType(attachment.getContentType());

          thumbnailData = BitmapUtil.createScaledBytes(context, new DecryptableStreamUriLoader.DecryptableUri(attachment.getDataUri()), 100, 100, 500 * 1024, format);
          thumbnailType = attachment.getContentType();
        } else if (MediaUtil.isVideoType(attachment.getContentType()) && attachment.getThumbnailUri() != null) {
          thumbnailData = BitmapUtil.createScaledBytes(context, new DecryptableStreamUriLoader.DecryptableUri(attachment.getThumbnailUri()), 100, 100, 500 * 1024);
        }

        if (thumbnailData != null) {
          thumbnail = SignalServiceAttachment.newStreamBuilder()
                                             .withContentType(thumbnailType)
                                             .withWidth(thumbnailData.getWidth())
                                             .withHeight(thumbnailData.getHeight())
                                             .withLength(thumbnailData.getBitmap().length)
                                             .withStream(new ByteArrayInputStream(thumbnailData.getBitmap()))
                                             .build();
        }

        quoteAttachments.add(new SignalServiceDataMessage.Quote.QuotedAttachment(attachment.getContentType(),
                                                                                 attachment.getFileName(),
                                                                                 thumbnail));
      } catch (BitmapDecodingException e) {
        Log.w(TAG, e);
      }
    }

    return Optional.of(new SignalServiceDataMessage.Quote(quoteId, new SignalServiceAddress(quoteAuthor.serialize()), quoteBody, quoteAttachments));
  }

  List<SharedContact> getSharedContactsFor(OutgoingMediaMessage mediaMessage) {
    List<SharedContact> sharedContacts = new LinkedList<>();

    for (Contact contact : mediaMessage.getSharedContacts()) {
      SharedContact.Builder builder = ContactModelMapper.localToRemoteBuilder(contact);
      SharedContact.Avatar  avatar  = null;

      if (contact.getAvatar() != null && contact.getAvatar().getAttachment() != null) {
        avatar = SharedContact.Avatar.newBuilder().withAttachment(getAttachmentFor(contact.getAvatarAttachment()))
                                                  .withProfileFlag(contact.getAvatar().isProfile())
                                                  .build();
      }

      builder.setAvatar(avatar);
      sharedContacts.add(builder.build());
    }

    return sharedContacts;
  }

  List<Preview> getPreviewsFor(OutgoingMediaMessage mediaMessage) {
    return Stream.of(mediaMessage.getLinkPreviews()).map(lp -> {
      SignalServiceAttachment attachment = lp.getThumbnail().isPresent() ? getAttachmentPointerFor(lp.getThumbnail().get()) : null;
      return new Preview(lp.getUrl(), lp.getTitle(), Optional.fromNullable(attachment));
    }).toList();
  }

  protected abstract void onPushSend() throws Exception;
}
