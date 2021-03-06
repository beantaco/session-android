package org.session.libsignal.utilities;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;

import org.json.JSONException;
import org.json.JSONObject;
import org.session.libsignal.libsignal.IdentityKey;
import org.session.libsignal.libsignal.InvalidKeyException;
import org.session.libsignal.utilities.logging.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;

public class JsonUtil {

  private static final String TAG = org.session.libsignal.utilities.JsonUtil.class.getSimpleName();

  private static final ObjectMapper objectMapper = new ObjectMapper();

  static {
    objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    objectMapper.enable(SerializationFeature.WRITE_ENUMS_USING_TO_STRING);
    objectMapper.enable(DeserializationFeature.READ_ENUMS_USING_TO_STRING);
  }

  public static <T> T fromJson(byte[] serialized, Class<T> clazz) throws IOException {
    return fromJson(new String(serialized), clazz);
  }

  public static <T> T fromJson(String serialized, Class<T> clazz) throws IOException {
    return objectMapper.readValue(serialized, clazz);
  }

  public static <T> T fromJson(InputStream serialized, Class<T> clazz) throws IOException {
    return objectMapper.readValue(serialized, clazz);
  }

  public static <T> T fromJson(Reader serialized, Class<T> clazz) throws IOException {
    return objectMapper.readValue(serialized, clazz);
  }

  public  static JsonNode fromJson(String serialized) throws IOException {
    return objectMapper.readTree(serialized);
  }

  public static String toJsonThrows(Object object) throws IOException {
    return objectMapper.writeValueAsString(object);
  }

    public static String toJson(Object object) {
    try {
      return objectMapper.writeValueAsString(object);
    } catch (JsonProcessingException e) {
      Log.w(TAG, e);
      return "";
    }
  }

  public static class IdentityKeySerializer extends JsonSerializer<IdentityKey> {
    @Override
    public void serialize(IdentityKey value, JsonGenerator gen, SerializerProvider serializers)
            throws IOException
    {
      gen.writeString(Base64.encodeBytesWithoutPadding(value.serialize()));
    }
  }

  public static class IdentityKeyDeserializer extends JsonDeserializer<IdentityKey> {
    @Override
    public IdentityKey deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
      try {
        return new IdentityKey(Base64.decodeWithoutPadding(p.getValueAsString()), 0);
      } catch (InvalidKeyException e) {
        throw new IOException(e);
      }
    }
  }

  public static ObjectMapper getMapper() {
    return objectMapper;
  }

  public static class SaneJSONObject {

    private final JSONObject delegate;

    public SaneJSONObject(JSONObject delegate) {
      this.delegate = delegate;
    }

    public String getString(String name) throws JSONException {
      if (delegate.isNull(name)) return null;
      else                       return delegate.getString(name);
    }

    public long getLong(String name) throws JSONException {
      return delegate.getLong(name);
    }

    public boolean isNull(String name) {
      return delegate.isNull(name);
    }

    public int getInt(String name) throws JSONException {
      return delegate.getInt(name);
    }
  }
}
