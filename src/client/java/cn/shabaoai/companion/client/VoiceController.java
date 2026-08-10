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

import cn.shabaoai.companion.net.AiPromptPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import javax.sound.sampled.*;
import java.io.*;
import java.util.concurrent.CompletableFuture;

public final class VoiceController {
    private static final AudioFormat FORMAT=new AudioFormat(16000,16,1,true,false);
    private static final long MAX_RECORDING_NANOS=30_000_000_000L;
    private final SpeechClient speech=new SpeechClient();
    private volatile boolean recording;
    private TargetDataLine line;
    private CompletableFuture<byte[]> task;
    private long startedAt;

    public void start(MinecraftClient client) {
        if(recording)return;
        try {
            line=AudioSystem.getTargetDataLine(FORMAT);line.open(FORMAT);line.start();recording=true;startedAt=System.nanoTime();
            task=CompletableFuture.supplyAsync(()->capture(line));
            toast(client,"§e沙包：识别中…（松开 V 发送）");
        } catch(LineUnavailableException e){toast(client,"§c麦克风不可用："+e.getMessage());}
    }

    public void stop(MinecraftClient client) {
        if(!recording)return;recording=false;line.stop();line.close();toast(client,"§e沙包：发送中…");
        task.thenCompose(speech::transcribe).thenAccept(text->client.execute(()->{
            toast(client,"§7你说："+text);ClientPlayNetworking.send(new AiPromptPayload(text));
        })).exceptionally(e->{client.execute(()->toast(client,"§c语音识别失败："+root(e).getMessage()));return null;});
    }

    public boolean isRecording(){return recording;}
    public boolean hasTimedOut(){return recording&&System.nanoTime()-startedAt>=MAX_RECORDING_NANOS;}

    public void speak(MinecraftClient client,String text){speech.speak(text).exceptionally(e->{client.execute(()->toast(client,"§c语音播放失败："+root(e).getMessage()));return null;});}

    private byte[] capture(TargetDataLine source){
        try(ByteArrayOutputStream raw=new ByteArrayOutputStream()){byte[] b=new byte[4096];while(recording){int n=source.read(b,0,b.length);if(n>0)raw.write(b,0,n);}byte[] pcm=raw.toByteArray();
            try(ByteArrayOutputStream wav=new ByteArrayOutputStream();AudioInputStream in=new AudioInputStream(new ByteArrayInputStream(pcm),FORMAT,pcm.length/FORMAT.getFrameSize())){AudioSystem.write(in,AudioFileFormat.Type.WAVE,wav);return wav.toByteArray();}
        }catch(IOException e){throw new UncheckedIOException(e);}
    }
    private static Throwable root(Throwable e){while(e.getCause()!=null)e=e.getCause();return e;}
    private static void toast(MinecraftClient c,String s){if(c.player!=null)c.player.sendMessage(Text.literal(s),false);}
}
