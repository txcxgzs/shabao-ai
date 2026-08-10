/*
 * Copyright (C) 2026 txcxgzs
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package cn.shabaoai.companion.client;

import cn.shabaoai.companion.config.ModConfig;
import com.google.gson.*;

import javax.sound.sampled.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

public final class SpeechClient {
    private static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private static final Gson GSON=new Gson();

    public CompletableFuture<String> transcribe(byte[] wav) {
        ModConfig.reload();
        ModConfig.Speech s=ModConfig.get().asr;
        if(wav.length==0)return CompletableFuture.failedFuture(new IllegalStateException("没有录到声音"));
        if(wav.length>7_500_000)return CompletableFuture.failedFuture(new IllegalStateException("录音过长，请控制在 30 秒以内"));
        if(blank(s.baseUrl()))return CompletableFuture.failedFuture(new IllegalStateException("ASR baseUrl 未配置"));
        JsonObject body=new JsonObject();body.addProperty("model",s.model());
        JsonObject audio=new JsonObject();audio.addProperty("data","data:audio/wav;base64,"+Base64.getEncoder().encodeToString(wav));
        JsonObject part=new JsonObject();part.addProperty("type","input_audio");part.add("input_audio",audio);
        JsonArray content=new JsonArray();content.add(part);JsonObject message=new JsonObject();message.addProperty("role","user");message.add("content",content);
        JsonArray messages=new JsonArray();messages.add(message);body.add("messages",messages);
        JsonObject options=new JsonObject();options.addProperty("language",blank(s.language())?"auto":s.language());body.add("asr_options",options);
        HttpRequest.Builder b=HttpRequest.newBuilder(endpoint(s)).timeout(Duration.ofSeconds(90))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)));
        auth(b,s.apiKey());
        return HTTP.sendAsync(b.build(),HttpResponse.BodyHandlers.ofString()).thenApply(r->{
            if(r.statusCode()/100!=2)throw new IllegalStateException("ASR HTTP "+r.statusCode());
            JsonObject j=JsonParser.parseString(r.body()).getAsJsonObject();
            JsonElement contentValue=j.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").get("content");
            if(contentValue==null)throw new IllegalStateException("ASR 响应缺少 choices[0].message.content");
            if(contentValue.isJsonPrimitive())return contentValue.getAsString();
            return contentValue.toString();
        });
    }

    public CompletableFuture<Void> speak(String text) {
        ModConfig.reload();
        ModConfig.Speech s=ModConfig.get().tts;
        if(blank(s.baseUrl()))return CompletableFuture.completedFuture(null);
        JsonObject body=new JsonObject();body.addProperty("model",s.model());
        JsonArray messages=new JsonArray();
        if(!blank(s.instruction()))messages.add(message("user",s.instruction()));
        messages.add(message("assistant",text));body.add("messages",messages);
        JsonObject audio=new JsonObject();audio.addProperty("format","wav");audio.addProperty("voice",blank(s.voice())?"mimo_default":s.voice());body.add("audio",audio);
        HttpRequest.Builder b=HttpRequest.newBuilder(endpoint(s)).timeout(Duration.ofSeconds(90)).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)));auth(b,s.apiKey());
        return HTTP.sendAsync(b.build(),HttpResponse.BodyHandlers.ofString()).thenAccept(r->{
            if(r.statusCode()/100!=2)throw new IllegalStateException("TTS HTTP "+r.statusCode());
            JsonObject root=JsonParser.parseString(r.body()).getAsJsonObject();
            String encoded=root.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").getAsJsonObject("audio").get("data").getAsString();
            playWav(Base64.getDecoder().decode(encoded));
        });
    }

    private static JsonObject message(String role,String content){JsonObject m=new JsonObject();m.addProperty("role",role);m.addProperty("content",content);return m;}
    private static void playWav(byte[] bytes){
        try(AudioInputStream in=AudioSystem.getAudioInputStream(new ByteArrayInputStream(bytes))){Clip clip=AudioSystem.getClip();clip.open(in);clip.addLineListener(e->{if(e.getType()==LineEvent.Type.STOP)clip.close();});clip.start();}
        catch(Exception e){throw new IllegalStateException("TTS 返回值不是可播放 WAV",e);}
    }
    private static URI endpoint(ModConfig.Speech s){String base=s.baseUrl();return URI.create((base.endsWith("/")?base.substring(0,base.length()-1):base)+"/chat/completions");}
    private static void auth(HttpRequest.Builder b,String key){if(!blank(key))b.header("api-key",key);}
    private static boolean blank(String s){return s==null||s.isBlank();}
}
