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
package com.arialyy.aria.core.common;

import android.net.TrafficStats;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Process;
import android.support.annotation.Nullable;
import com.arialyy.aria.core.AriaManager;
import com.arialyy.aria.core.config.BaseTaskConfig;
import com.arialyy.aria.core.config.DGroupConfig;
import com.arialyy.aria.core.config.DownloadConfig;
import com.arialyy.aria.core.config.UploadConfig;
import com.arialyy.aria.core.inf.AbsNormalEntity;
import com.arialyy.aria.core.inf.AbsTaskWrapper;
import com.arialyy.aria.core.inf.ITaskWrapper;
import com.arialyy.aria.core.manager.ThreadTaskManager;
import com.arialyy.aria.exception.BaseException;
import com.arialyy.aria.util.ALog;
import com.arialyy.aria.util.BufferedRandomAccessFile;
import com.arialyy.aria.util.CommonUtil;
import com.arialyy.aria.util.ErrorHelp;
import com.arialyy.aria.util.NetUtils;
import java.io.File;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by lyy on 2017/1/18. 任务线程
 */
public abstract class AbsThreadTask<ENTITY extends AbsNormalEntity, TASK_WRAPPER extends AbsTaskWrapper<ENTITY>>
    implements Callable<AbsThreadTask> {
  /**
   * 线程重试次数
   */
  private final int RETRY_NUM = 2;

  private final String TAG = "AbsThreadTask";
  /**
   * 当前子线程相对于总长度的位置
   */
  protected long mChildCurrentLocation = 0;
  private ENTITY mEntity;
  protected TASK_WRAPPER mTaskWrapper;
  private int mFailTimes = 0;
  private long mLastSaveTime;
  private ExecutorService mConfigThreadPool;
  private boolean isNotNetRetry;  //断网情况是否重试
  private boolean taskBreak = false;  //任务跳出
  protected BandwidthLimiter mSpeedBandUtil; //速度限制工具
  protected AriaManager mAridManager;
  private boolean isInterrupted = false;
  protected boolean isCancel = false, isStop = false;
  protected ThreadRecord mRecord;
  private Handler mStateHandler;
  private SubThreadConfig<TASK_WRAPPER> mConfig;

  private Thread mConfigThread = new Thread(new Runnable() {
    @Override public void run() {
      Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
      final long currentTemp = mChildCurrentLocation;
      writeConfig(false, currentTemp);
    }
  });

  protected AbsThreadTask(SubThreadConfig<TASK_WRAPPER> config) {
    mConfig = config;
    mTaskWrapper = config.taskWrapper;
    mRecord = config.record;
    mStateHandler = config.stateHandler;
    mEntity = mTaskWrapper.getEntity();
    mLastSaveTime = System.currentTimeMillis();
    mConfigThreadPool = Executors.newCachedThreadPool();
    mAridManager = AriaManager.getInstance(AriaManager.APP);
    if (getMaxSpeed() > 0) {
      mSpeedBandUtil = new BandwidthLimiter(getMaxSpeed(), config.startThreadNum);
    }
    isNotNetRetry = mAridManager.getAppConfig().isNotNetRetry();
    mChildCurrentLocation = mRecord.startLocation;
  }

  /**
   * 当前线程处理的文件名
   */
  protected String getFileName() {
    return mConfig.tempFile.getName();
  }

  protected SubThreadConfig<TASK_WRAPPER> getConfig() {
    return mConfig;
  }

  /**
   * 设置线程是否中断
   *
   * @param isInterrupted {@code true} 中断
   */
  public void setInterrupted(boolean isInterrupted) {
    this.isInterrupted = isInterrupted;
  }

  /**
   * 线程是否存活
   *
   * @return {@code true}存活
   */
  protected boolean isLive() {
    return !Thread.currentThread().isInterrupted() && !isInterrupted;
  }

  /**
   * 当前线程是否完成，对于不支持断点的任务，一律未完成 {@code true} 完成；{@code false} 未完成
   */
  boolean isThreadComplete() {
    return mRecord.isComplete;
  }

  /**
   * 获取实体
   */
  protected ENTITY getEntity() {
    return mEntity;
  }

  /**
   * 获取任务驱动对象
   */
  protected TASK_WRAPPER getTaskWrapper() {
    return mTaskWrapper;
  }

  /**
   * 获取配置的最大上传/下载速度
   *
   * @return 单位为：kb
   */
  public abstract int getMaxSpeed();

  /**
   * 读取任务配置
   *
   * @return {@link DownloadConfig}、{@link UploadConfig}、{@link DGroupConfig}
   */
  protected abstract BaseTaskConfig getTaskConfig();

  /**
   * 设置当前线程最大下载速度
   *
   * @param speed 单位为：kb
   */
  public void setMaxSpeed(int speed) {
    if (mSpeedBandUtil != null) {
      mSpeedBandUtil.setMaxRate(speed);
    }
  }

  /**
   * 中断任务
   */
  void breakTask() {
    synchronized (AriaManager.LOCK) {
      taskBreak = true;
      if (mTaskWrapper.isSupportBP()) {
        final long currentTemp = mChildCurrentLocation;
        sendState(IThreadState.STATE_STOP, null);
        ALog.d(TAG, String.format("任务【%s】thread__%s__中断【停止位置：%s】", getFileName(),
            mRecord.threadId, currentTemp));
        writeConfig(false, currentTemp);
      } else {
        ALog.i(TAG, String.format("任务【%s】已中断", getFileName()));
      }
    }
  }

  /**
   * 发送状态给状态处理器
   *
   * @param state {@link IThreadState#STATE_STOP}..
   * @param bundle 而外数据
   */
  void sendState(int state, @Nullable Bundle bundle) {
    Message msg = mStateHandler.obtainMessage();
    msg.what = state;
    msg.obj = this;
    if (bundle != null) {
      msg.setData(bundle);
    }
    msg.sendToTarget();
  }

  public boolean isInterrupted() {
    return Thread.currentThread().isInterrupted();
  }

  @Override protected void finalize() throws Throwable {
    super.finalize();
    if (mConfigThreadPool != null) {
      mConfigThreadPool.shutdown();
    }
  }

  /**
   * 任务是否中断，中断条件： 1、任务取消 2、任务停止 3、手动中断 {@link #taskBreak}
   *
   * @return {@code true} 中断，{@code false} 不是中断
   */
  protected boolean isBreak() {
    return isCancel || isStop || taskBreak;
  }

  /**
   * 检查下载完成的分块大小，如果下载完成的分块大小大于或小于分配的大小，则需要重新下载该分块 如果是非分块任务，直接返回{@code true}
   *
   * @return {@code true} 分块分大小正常，{@code false} 分块大小错误
   */
  protected boolean checkBlock() {
    if (!mConfig.isBlock) {
      return true;
    }
    File blockFile = mConfig.tempFile;
    if (!blockFile.exists() || blockFile.length() != mRecord.blockLen) {
      ALog.i(TAG,
          String.format("分块【%s】错误，blockFileLen: %s, threadRect: %s; 即将重新下载该分块，开始位置：%s，结束位置：%s",
              blockFile.getName(), blockFile.length(), mRecord.blockLen, mRecord.startLocation,
              mRecord.endLocation));
      if (blockFile.exists()) {
        blockFile.delete();
        ALog.i(TAG, String.format("删除分块【%s】成功", blockFile.getName()));
      }
      retryBlockTask(isBreak());
      return false;
    }
    return true;
  }

  /**
   * 停止任务
   */
  public void stop() {
    isStop = true;
    sendState(IThreadState.STATE_STOP, null);
    if (mTaskWrapper.getRequestType() == ITaskWrapper.M3U8_VOD) {
      writeConfig(false, getConfig().tempFile.length());
      ALog.i(TAG, String.format("任务【%s】已停止", getFileName()));
    } else {
      if (mTaskWrapper.isSupportBP()) {
        final long stopLocation = mChildCurrentLocation;
        ALog.d(TAG,
            String.format("任务【%s】thread__%s__停止【当前线程停止位置：%s】", getFileName(),
                mRecord.threadId, stopLocation));
        writeConfig(false, stopLocation);
      } else {
        ALog.i(TAG, String.format("任务【%s】已停止", getFileName()));
      }
    }
  }

  /**
   * 执行中
   */
  protected void progress(long len) {
    synchronized (AriaManager.LOCK) {
      //if (getState().CURRENT_LOCATION > getEntity().getFileSize() && !getTaskWrapper().asHttp()
      //    .isChunked()) {
      //  String errorMsg =
      //      String.format("下载失败，下载长度超出文件真实长度；currentLocation=%s, fileSize=%s",
      //          getState().CURRENT_LOCATION,
      //          getEntity().getFileSize());
      //  taskBreak = true;
      //  fail(mChildCurrentLocation, new FileException(TAG, errorMsg), false);
      //  return;
      //}
      mChildCurrentLocation += len;
      if (!mStateHandler.getLooper().getThread().isAlive()) {
        return;
      }
      mStateHandler.obtainMessage(IThreadState.STATE_RUNNING, len).sendToTarget();
      if (System.currentTimeMillis() - mLastSaveTime > 5000
          && mChildCurrentLocation < mRecord.endLocation) {
        mLastSaveTime = System.currentTimeMillis();
        if (!mConfigThreadPool.isShutdown()) {
          mConfigThreadPool.execute(mConfigThread);
        }
      }
    }
  }

  /**
   * 取消任务
   */
  public void cancel() {
    isCancel = true;
    sendState(IThreadState.STATE_CANCEL, null);
    ALog.d(TAG,
        String.format("任务【%s】thread__%s__取消", getFileName(), mRecord.threadId));
  }

  /**
   * 线程任务失败
   *
   * @param subCurrentLocation 当前线程下载进度
   * @param ex 异常信息
   */
  protected void fail(long subCurrentLocation, BaseException ex) {
    fail(subCurrentLocation, ex, true);
  }

  /**
   * 任务失败
   *
   * @param subCurrentLocation 当前子线程进度
   */
  protected void fail(final long subCurrentLocation, BaseException ex, boolean needRetry) {
    if (ex != null) {
      ALog.e(TAG, ALog.getExceptionString(ex));
    }
    if (mTaskWrapper.getRequestType() == ITaskWrapper.M3U8_VOD) {
      writeConfig(false, 0);
      retryM3U8Peer(needRetry);
    } else {
      if (mTaskWrapper.isSupportBP()) {
        writeConfig(false, subCurrentLocation);
        retryBlockTask(needRetry && mConfig.startThreadNum != 1);
      } else {
        ALog.e(TAG, String.format("任务【%s】执行失败", getFileName()));
        ErrorHelp.saveError(TAG, "", ALog.getExceptionString(ex));
        sendFailMsg(null);
      }
    }
  }

  /**
   * 重试ts分片
   */
  private void retryM3U8Peer(boolean needRetry) {
    if (!NetUtils.isConnected(AriaManager.APP) && !isNotNetRetry) {
      ALog.w(TAG, String.format("任务【%s】重试失败，网络未连接", getFileName()));
      sendFailMsg(null);
      return;
    }
    if (mFailTimes < RETRY_NUM && needRetry && (NetUtils.isConnected(AriaManager.APP)
        || isNotNetRetry) && !isBreak()) {
      ALog.w(TAG, String.format("任务【%s】正在重试", getFileName()));
      mFailTimes++;
      mConfig.tempFile.delete();
      CommonUtil.createFile(mConfig.tempFile.getPath());
      ThreadTaskManager.getInstance().retryThread(this);
    } else {
      sendFailMsg(null);
    }
  }

  /**
   * 重试分块线程，如果其中一条线程已经下载失败，则任务该任务下载失败，并且停止该任务的所有线程
   *
   * @param needRetry 是否可以重试
   */
  private void retryBlockTask(boolean needRetry) {
    if (!NetUtils.isConnected(AriaManager.APP) && !isNotNetRetry) {
      ALog.w(TAG, String.format("任务【%s】重试失败，网络未连接", getFileName()));
      sendFailMsg(null);
      return;
    }
    if (mFailTimes < RETRY_NUM && needRetry && (NetUtils.isConnected(AriaManager.APP)
        || isNotNetRetry) && !isBreak()) {
      ALog.w(TAG, String.format("任务【%s】正在重试", getFileName()));
      mFailTimes++;
      handleBlockRecord();
      ThreadTaskManager.getInstance().retryThread(this);
    } else {
      sendFailMsg(null);
    }
  }

  /**
   * 处理线程重试的分块记录，只有多线程任务才会执行
   * 如果是以前版本{@link BufferedRandomAccessFile}创建的下载，那么 record.startLocation不用修改
   */
  private void handleBlockRecord() {
    if (mConfig.isBlock) {
      // 默认线程分块长度
      File temp = mConfig.tempFile;

      long blockFileLen = temp.length(); // 磁盘中的分块文件长度
      long threadRect = mRecord.blockLen;     // 当前线程的区间

      if (!temp.exists()) {
        ALog.i(TAG, String.format("分块文件【%s】不存在，该分块将重新开始", temp.getName()));
        mRecord.isComplete = false;
        mRecord.startLocation = mRecord.endLocation - threadRect;
      } else {
        /*
         * 检查磁盘中的分块文件
         */
        if (blockFileLen > threadRect) {
          ALog.i(TAG, String.format("分块【%s】错误，将重新下载该分块", temp.getName()));
          temp.delete();
          mRecord.startLocation = mRecord.endLocation - mRecord.blockLen;
          mRecord.isComplete = false;
        } else if (blockFileLen < mRecord.blockLen) {
          mRecord.startLocation = mRecord.endLocation - mRecord.blockLen + blockFileLen;
          mRecord.isComplete = false;
          sendState(IThreadState.STATE_UPDATE_PROGRESS, null);
          ALog.i(TAG,
              String.format("修正分块【%s】记录，开始位置：%s，结束位置：%s", temp.getName(), mRecord.startLocation,
                  mRecord.endLocation));
        } else {
          ALog.i(TAG, String.format("分块【%s】已完成，更新记录", temp.getName()));
          mRecord.isComplete = true;
        }
      }
      mRecord.update();
    }
  }

  /**
   * 发送任务完成的消息，并删除任务记录
   */
  protected synchronized void sendCompleteMsg() {
    ALog.i(TAG, String.format("任务【%s】完成", getFileName()));
    sendState(IThreadState.STATE_COMPLETE, null);
  }
  protected synchronized void sendFileSizeMsg(int fileSize) {
    ALog.i(TAG, String.format("【%s】文件大小：", getFileName())+fileSize);
    Bundle b = new Bundle();
    b.putSerializable(IThreadState.KEY_ITEM_FILE_SIZE, fileSize);
    sendState(IThreadState.STATE_ITEM_FILE_SIZE, b);
  }


  /**
   * 发送失败信息
   */
  protected void sendFailMsg(@Nullable BaseException e) {
    if (e != null) {
      Bundle b = new Bundle();
      b.putSerializable(IThreadState.KEY_ERROR_INFO, e);
      sendState(IThreadState.STATE_FAIL, b);
    } else {
      sendState(IThreadState.STATE_FAIL, null);
    }
  }

  /**
   * 将记录写入到配置文件
   *
   * @param isComplete 当前线程是否完成 {@code true}完成
   * @param record 当前下载进度
   */
  protected void writeConfig(boolean isComplete, final long record) {
    if (mRecord != null) {
      mRecord.isComplete = isComplete;
      if (mConfig.isBlock) {
        mRecord.startLocation = record;
      } else if (mConfig.isOpenDynamicFile) {
        mRecord.startLocation = mConfig.tempFile.length();
      } else {
        if (0 < record && record < mRecord.endLocation) {
          mRecord.startLocation = record;
        }
      }
      mRecord.update();
    }
  }

  @Override public AbsThreadTask call() throws Exception {
    isInterrupted = false;
    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
    TrafficStats.setThreadStatsTag(UUID.randomUUID().toString().hashCode());
    return this;
  }
}
