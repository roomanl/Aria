/*
 * Copyright (C) 2016 AriaLyy(https://github.com/AriaLyy/Aria)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.arialyy.aria.core.download.m3u8;

import android.net.TrafficStats;
import android.os.Process;
import android.text.TextUtils;
import com.arialyy.aria.core.AriaManager;
import com.arialyy.aria.core.common.CompleteInfo;
import com.arialyy.aria.core.common.OnFileInfoCallback;
import com.arialyy.aria.core.common.http.HttpTaskConfig;
import com.arialyy.aria.core.download.DTaskWrapper;
import com.arialyy.aria.core.download.DownloadEntity;
import com.arialyy.aria.core.download.downloader.ConnectionHelp;
import com.arialyy.aria.core.inf.ITaskWrapper;
import com.arialyy.aria.exception.M3U8Exception;
import com.arialyy.aria.exception.TaskException;
import com.arialyy.aria.util.ALog;
import com.arialyy.aria.util.CheckUtil;
import com.arialyy.aria.util.CommonUtil;
import com.arialyy.aria.util.Regular;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析url中获取到到m3u8文件信息
 * https://www.cnblogs.com/renhui/p/10351870.html
 * https://blog.csdn.net/Guofengpu/article/details/54922865
 */
final class M3U8InfoThread implements Runnable {
  private final String TAG = "M3U8FileInfoThread";
  private DownloadEntity mEntity;
  private DTaskWrapper mTaskWrapper;
  private int mConnectTimeOut;
  private OnFileInfoCallback onFileInfoCallback;
  private OnGetLivePeerCallback onGetPeerCallback;
  private HttpTaskConfig mTaskDelegate;
  /**
   * 是否停止获取切片信息，{@code true}停止获取切片信息
   */
  private boolean isStop = false;
  private String m3u8BaseUrl=null;

  interface OnGetLivePeerCallback {
    void onGetPeer(String url);
  }

  M3U8InfoThread(DTaskWrapper taskWrapper, OnFileInfoCallback callback) {
    this.mTaskWrapper = taskWrapper;
    mEntity = taskWrapper.getEntity();
    mConnectTimeOut =
        AriaManager.getInstance(AriaManager.APP).getDownloadConfig().getConnectTimeOut();
    onFileInfoCallback = callback;
    mTaskDelegate = taskWrapper.asHttp();
  }

  @Override public void run() {
    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
    TrafficStats.setThreadStatsTag(UUID.randomUUID().toString().hashCode());
    HttpURLConnection conn = null;
    try {
      URL url = ConnectionHelp.handleUrl(mEntity.getUrl(), mTaskDelegate);
      conn = ConnectionHelp.handleConnection(url, mTaskDelegate);
      ConnectionHelp.setConnectParam(mTaskDelegate, conn);
      conn.setConnectTimeout(mConnectTimeOut);
      conn.connect();
      handleConnect(conn);
    } catch (IOException e) {
      e.printStackTrace();
      failDownload("Url无效："+e.getMessage(), false);
    } finally {
      if (conn != null) {
        conn.disconnect();
      }
    }
  }

  private void handleConnect(HttpURLConnection conn) throws IOException {
    int code = conn.getResponseCode();
    if (code == HttpURLConnection.HTTP_OK) {
      BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
      String line = reader.readLine();
      if (TextUtils.isEmpty(line) || !line.equalsIgnoreCase("#EXTM3U")) {
        failDownload("读取M3U8信息失败，读取不到#EXTM3U标签", false);
        return;
      }
      List<String> extInf = new ArrayList<>();
      boolean isLive = mTaskWrapper.getRequestType() == ITaskWrapper.M3U8_LIVE;
      while ((line = reader.readLine()) != null) {
        if (isStop){
          break;
        }
        if (line.startsWith("#EXT-X-ENDLIST")) {
          break;
        }
        ALog.d(TAG, line);
        if (line.startsWith("#EXTINF")) {
          String info = reader.readLine();
          if (isLive) {
            if (onGetPeerCallback != null) {
              onGetPeerCallback.onGetPeer(info);
            }
          } else {
            if(info.indexOf("http")<0){
              if(m3u8BaseUrl==null){
                m3u8BaseUrl = mEntity.getUrl().substring(0, mEntity.getUrl().lastIndexOf("/")+1 );
              }
              info=m3u8BaseUrl+info;
            }
            extInf.add(info);
          }
        } else if (line.startsWith("#EXT-X-STREAM-INF")) {
          int setBand = mTaskWrapper.asM3U8().getBandWidth();
          int bandWidth = getBandWidth(line);
          if (setBand == 0 || bandWidth == setBand) {
            String m3u8Url=getM3u8Url(mEntity.getUrl(),reader.readLine(),true);
            if(m3u8Url==null){
              failDownload("Url无效", false);
              return;
            }
            handleBandWidth(conn, m3u8Url);
          } else {
            failDownload(String.format("【%s】码率不存在", bandWidth), false);
          }
          return;
        } else if (line.startsWith("EXT-X-KEY")) {
          getKeyInfo(line);
        }
      }

      if (!isLive && extInf.isEmpty()) {
        failDownload(String.format("获取M3U8下载地址列表失败，url: %s", mEntity.getUrl()), false);
        return;
      }
      CompleteInfo info = new CompleteInfo();
      info.obj = extInf;
      onFileInfoCallback.onComplete(mEntity.getKey(), info);
    } else if (code == HttpURLConnection.HTTP_MOVED_TEMP
        || code == HttpURLConnection.HTTP_MOVED_PERM
        || code == HttpURLConnection.HTTP_SEE_OTHER
        || code == HttpURLConnection.HTTP_CREATED // 201 跳转
        || code == 307) {
      handleUrlReTurn(conn, conn.getHeaderField("Location"));
    } else if (code == HttpURLConnection.HTTP_NOT_FOUND) {
      failDownload("404错误", false);
    } else {
      failDownload(String.format("不支持的响应，code: %s", code), true);
    }
  }

  public String getM3u8Url(String parentUrl,String subUrl,boolean last){
    if(subUrl.indexOf("http")>-1) {
      return subUrl;
    }
      String basepath="";
      if(parentUrl.indexOf(".com")>-1){
        basepath=parentUrl.substring(0, parentUrl.lastIndexOf(".com") + 4);
      }else if(parentUrl.indexOf(".cn")>-1){
        basepath=parentUrl.substring(0, parentUrl.lastIndexOf(".cn") + 3);
      }else if(parentUrl.indexOf(".vip")>-1){
        basepath=parentUrl.substring(0, parentUrl.lastIndexOf(".vip") + 4);
      }else if(parentUrl.indexOf(".net")>-1){
        basepath=parentUrl.substring(0, parentUrl.lastIndexOf(".net") + 4);
      }else if(parentUrl.indexOf(".org")>-1){
        basepath=parentUrl.substring(0, parentUrl.lastIndexOf(".org") + 4);
      }
      if(!last){
         basepath = parentUrl.substring(0, parentUrl.lastIndexOf("/")+1 );
      }
      String connUrl=basepath+subUrl;
      if(subUrl.startsWith("/") || basepath.endsWith("/")){
        connUrl=basepath+subUrl;
      }else if(!subUrl.startsWith("/") && !basepath.endsWith("/")){
        connUrl=basepath+"/"+subUrl;
      }
      try {
        HttpURLConnection conn = (HttpURLConnection) new URL(connUrl).openConnection();
        if (conn.getResponseCode() == 200) {
          m3u8BaseUrl=basepath;
          return connUrl;
        }else {
          return getM3u8Url(parentUrl,subUrl,false);
        }
      } catch (IOException e) {
        e.printStackTrace();
        return null;//getM3u8Url(parentUrl,subUrl,false);
      }
  }

  /**
   * 是否停止获取切片信息，{@code true}停止获取切片信息
   */
  public void setStop(boolean isStop) {
    this.isStop = isStop;
  }

  /**
   * 直播切片信息获取回调
   */
  public void setOnGetPeerCallback(OnGetLivePeerCallback peerCallback) {
    onGetPeerCallback = peerCallback;
  }

  /**
   * 获取加密的密钥信息
   */
  private void getKeyInfo(String line) {
    String temp = line.substring(line.indexOf(":") + 1);
    String[] params = temp.split(",");
    M3U8KeyInfo keyInfo = new M3U8KeyInfo();
    for (String param : params) {
      if (param.startsWith("METHOD")) {
        keyInfo.method = param.split("=")[1];
      } else if (param.startsWith("URI")) {
        keyInfo.keyUrl = param.split("=")[1].replaceAll("\"", "");
        keyInfo.keyPath = new File(mEntity.getFilePath()).getParent() + "/" + CommonUtil.getStrMd5(
            keyInfo.keyUrl) + ".key";
      } else if (param.startsWith("IV")) {
        keyInfo.iv = param.split("=")[1];
      }
    }
    mTaskWrapper.asM3U8().setKeyInfo(keyInfo);
    DownloadKey(keyInfo);
  }

  /**
   * 读取bandwidth
   */
  private int getBandWidth(String line) {
    Pattern p = Pattern.compile(Regular.BANDWIDTH);
    Matcher m = p.matcher(line);
    if (m.find()) {
      return Integer.parseInt(m.group());
    }
    return 0;
  }

  /**
   * 处理30x跳转
   */
  private void handleUrlReTurn(HttpURLConnection conn, String newUrl) throws IOException {
    ALog.d(TAG, "30x跳转，新url为【" + newUrl + "】");
    if (TextUtils.isEmpty(newUrl) || newUrl.equalsIgnoreCase("null") || !newUrl.startsWith(
        "http")) {
      if (onFileInfoCallback != null) {
        onFileInfoCallback.onFail(mEntity, new TaskException(TAG, "获取重定向链接失败"), false);
      }
      return;
    }
    if (!CheckUtil.checkUrlNotThrow(newUrl)) {
      failDownload("下载失败，重定向url错误", false);
      return;
    }
    mTaskDelegate.setRedirectUrl(newUrl);
    mEntity.setRedirect(true);
    mEntity.setRedirectUrl(newUrl);
    String cookies = conn.getHeaderField("Set-Cookie");
    conn.disconnect(); // 关闭上一个连接
    URL url = ConnectionHelp.handleUrl(newUrl, mTaskDelegate);
    conn = ConnectionHelp.handleConnection(url, mTaskDelegate);
    ConnectionHelp.setConnectParam(mTaskDelegate, conn);
    conn.setRequestProperty("Cookie", cookies);
    conn.setConnectTimeout(mConnectTimeOut);
    conn.connect();
    handleConnect(conn);
    conn.disconnect();
  }

  /**
   * 处理码率
   */
  private void handleBandWidth(HttpURLConnection conn, String bandWidthM3u8Url) throws IOException {
    IBandWidthUrlConverter converter = mTaskWrapper.asM3U8().getBandWidthUrlConverter();
    if (converter != null) {
      bandWidthM3u8Url = converter.convert(bandWidthM3u8Url);
      if (converter.getClass().isAnonymousClass()) {
        mTaskWrapper.asM3U8().setBandWidthUrlConverter(null);
      }
      if (!bandWidthM3u8Url.startsWith("http")) {
        failDownload(String.format("码率转换器转换后的url地址无效，转换后的url：%s", bandWidthM3u8Url), false);
        return;
      }
    }
    mTaskWrapper.asM3U8().setBandWidthUrl(bandWidthM3u8Url);
    ALog.d(TAG, String.format("新码率url：%s", bandWidthM3u8Url));
    String cookies = conn.getHeaderField("Set-Cookie");
    conn.disconnect();    // 关闭上一个连接
    URL url = ConnectionHelp.handleUrl(bandWidthM3u8Url, mTaskDelegate);
    conn = ConnectionHelp.handleConnection(url, mTaskDelegate);
    ConnectionHelp.setConnectParam(mTaskDelegate, conn);
    conn.setRequestProperty("Cookie", cookies);
    conn.setConnectTimeout(mConnectTimeOut);
    conn.connect();
    handleConnect(conn);
    conn.disconnect();
  }

  private void failDownload(String errorInfo, boolean needRetry) {
    onFileInfoCallback.onFail(mEntity, new M3U8Exception(TAG, errorInfo), needRetry);
  }

  /**
   * 密钥不存在，下载密钥
   */
  private void DownloadKey(M3U8KeyInfo info) {
    HttpURLConnection conn = null;
    FileOutputStream fos = null;
    try {
      File keyF = new File(info.keyPath);
      if (!keyF.exists()) {
        ALog.d(TAG, "密钥不存在，下载密钥");
        CommonUtil.createFile(keyF.getPath());
      } else {
        return;
      }
      URL url = ConnectionHelp.handleUrl(info.keyUrl, mTaskDelegate);
      conn = ConnectionHelp.handleConnection(url, mTaskDelegate);
      ConnectionHelp.setConnectParam(mTaskDelegate, conn);
      conn.setConnectTimeout(mConnectTimeOut);
      conn.connect();
      InputStream is = conn.getInputStream();
      fos = new FileOutputStream(keyF);
      byte[] buffer = new byte[1024];
      int len;
      while ((len = is.read(buffer)) != -1) {
        fos.write(buffer, 0, len);
      }
    } catch (Exception e) {
      e.printStackTrace();
    } finally {
      try {
        if (fos != null) {
          fos.close();
        }
        if (conn != null) {
          conn.disconnect();
        }
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }
}
