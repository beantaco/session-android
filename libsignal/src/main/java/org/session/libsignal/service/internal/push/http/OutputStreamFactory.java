package org.session.libsignal.service.internal.push.http;


import org.session.libsignal.service.api.crypto.DigestingOutputStream;

import java.io.IOException;
import java.io.OutputStream;

public interface OutputStreamFactory {

  public DigestingOutputStream createFor(OutputStream wrap) throws IOException;

}
