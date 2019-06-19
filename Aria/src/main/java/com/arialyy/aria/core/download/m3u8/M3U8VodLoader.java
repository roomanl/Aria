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

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import com.arialyy.aria.core.common.AbsThreadTask;
import com.arialyy.aria.core.common.IThreadState;
import com.arialyy.aria.core.common.SubThreadConfig;
import com.arialyy.aria.core.common.TaskRecord;
import com.arialyy.aria.core.common.ThreadRecord;
import com.arialyy.aria.core.download.DTaskWrapper;
import com.arialyy.aria.core.inf.IEventListener;
import com.arialyy.aria.core.manager.ThreadTaskManager;
import com.arialyy.aria.exception.BaseException;
import com.arialyy.aria.util.ALog;
import com.arialyy.aria.util.CommonUtil;
import com.arialyy.aria.util.FileUtil;
import com.arialyy.aria.util.IdGenerator;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * M3U8点播文件下载器
 */
public class M3U8VodLoader extends BaseM3U8Loader {
  /**
   * 最大执行数
   */
  private static final int EXEC_MAX_NUM = 4;
  private Handler mStateHandler;
  private ArrayBlockingQueue<Long> mFlagQueue = new ArrayBlockingQueue<>(EXEC_MAX_NUM);
  private VodStateManager mManager;
  private ReentrantLock LOCK = new ReentrantLock();
  private Condition mCondition = LOCK.newCondition();
  private long prvTime=0;
  private long prvLength=0;

  M3U8VodLoader(IEventListener listener, DTaskWrapper wrapper) {
    super(listener, wrapper);
  }

  @Override protected IThreadState getStateManager(Looper looper) {
    mManager = new VodStateManager(looper, mRecord, mListener);
    mStateHandler = new Handler(looper, mManager);
    return mManager;
  }

  @Override protected void handleTask() {

    new Thread(new Runnable() {
      @Override public void run() {
        int index = 0;
        String cacheDir = getCacheDir();
        while (!isBreak()) {
          try {
            LOCK.lock();
            while (mFlagQueue.size() < EXEC_MAX_NUM) {
              if (index == mRecord.threadRecords.size()) {
                break;
              }
              ThreadRecord tr = mRecord.threadRecords.get(index);
              index++;
              if (tr.isComplete) {
                continue;
              }

              M3U8ThreadTask task = createThreadTask(cacheDir, tr);
              getTaskList().put(tr.threadId, task);
              mFlagQueue.offer(startThreadTask(task));
            }
            if (mFlagQueue.size() > 0) {
              mCondition.await();
            }
          } catch (InterruptedException e) {
            e.printStackTrace();
          } finally {
            LOCK.unlock();
          }
        }
      }
    }).start();
  }

  @Override protected void setMaxSpeed(int maxSpeed) {
    // TODO: 2019-06-05 暂不支持

  }

  @Override protected void onStop() {
    super.onStop();
    //notifyLock();
  }

  @Override protected void onCancel() {
    super.onCancel();
    //notifyLock();
  }

  private void notifyLock() {
    try {
      LOCK.lock();
      long id = mFlagQueue.take();
      ALog.d(TAG, String.format("线程【%s】完成", id));
      mCondition.signalAll();
    } catch (InterruptedException e) {
      e.printStackTrace();
    } finally {
      LOCK.unlock();
    }
  }

  /**
   * 启动线程任务
   *
   * @return 线程唯一id标志
   */
  private long startThreadTask(M3U8ThreadTask task) {
    ThreadTaskManager.getInstance().startThread(mTaskWrapper.getKey(), task);
    return IdGenerator.getInstance().nextId();
  }

  /**
   * 配置config
   */
  private M3U8ThreadTask createThreadTask(String cacheDir, ThreadRecord record) {
    SubThreadConfig<DTaskWrapper> config = new SubThreadConfig<>();
    config.url = record.tsUrl;
    config.tempFile = new File(getTsFilePath(cacheDir, record.threadId));
    config.isBlock = mRecord.isBlock;
    config.isOpenDynamicFile = mRecord.isOpenDynamicFile;
    config.taskWrapper = mTaskWrapper;
    config.record = record;
    config.stateHandler = mStateHandler;
    if (!config.tempFile.exists()) {
      CommonUtil.createFile(config.tempFile.getPath());
    }
    return new M3U8ThreadTask(config);
  }

  /**
   * M3U8线程状态管理
   */
  private class VodStateManager implements IThreadState {
    private final String TAG = "M3U8ThreadStateManager";

    /**
     * 任务状态回调
     */
    private IEventListener mListener;
    private int mStartThreadNum;    // 启动的线程总数
    private int mCancelNum = 0; // 已经取消的线程的数
    private int mStopNum = 0;  // 已经停止的线程数
    private int mFailNum = 0;  // 失败的线程数
    private int mCompleteNum = 0;  // 完成的线程数
    private long mProgress; //当前总进度
    private TaskRecord mTaskRecord; // 任务记录
    private Looper mLooper;

    /**
     * @param taskRecord 任务记录
     * @param listener 任务事件
     */
    VodStateManager(Looper looper, TaskRecord taskRecord, IEventListener listener) {
      mLooper = looper;
      mTaskRecord = taskRecord;
      for (ThreadRecord record : taskRecord.threadRecords) {
        if (!record.isComplete) {
          mStartThreadNum++;
        }
      }
      mListener = listener;
    }

    /**
     * 退出looper循环
     */
    private void quitLooper() {
      ALog.d(TAG, "quitLooper");
      mLooper.quit();
    }

    @Override public boolean handleMessage(Message msg) {
      switch (msg.what) {
        case STATE_STOP:
          mStopNum++;
          ThreadTaskManager.getInstance().removeSingleTaskThread(mTaskWrapper.getKey(),
              (AbsThreadTask) msg.obj);
          if (isStop()) {
            ALog.d(TAG, "任务停止");
            mListener.onStop(mProgress);
            quitLooper();
          }
          break;
        case STATE_CANCEL:
          mCancelNum++;
          ThreadTaskManager.getInstance().removeSingleTaskThread(mTaskWrapper.getKey(),
              (AbsThreadTask) msg.obj);
          if (isCancel()) {
            ALog.d(TAG, "任务取消");
            mListener.onCancel();
            quitLooper();
          }
          break;
        case STATE_FAIL:
          mFailNum++;
          if (isFail()) {
            Bundle b = msg.getData();
            mListener.onFail(b.getBoolean(KEY_RETRY, true),
                (BaseException) b.getSerializable(KEY_ERROR_INFO));
            quitLooper();
          }
          break;
        case STATE_COMPLETE:
          mCompleteNum++;
          ThreadTaskManager.getInstance().removeSingleTaskThread(mTaskWrapper.getKey(),
              (AbsThreadTask) msg.obj);
          handlerPercent();
          notifyLock();
          if (isComplete()) {
            ALog.d(TAG, "isComplete, completeNum = " + mCompleteNum);
            if (mTaskWrapper.asM3U8().isMergeFile()) {
              if (mergeFile()) {
                mListener.onComplete();
              } else {
                mListener.onFail(false, null);
              }
            } else {
              mListener.onComplete();
            }
            quitLooper();
          }
          break;
        case STATE_RUNNING:
          if(prvTime==0){
            prvTime=System.currentTimeMillis();
            prvLength=mProgress;
          }else if(System.currentTimeMillis()-prvTime>=1000){
            mEntity.setSpeed2(mProgress-prvLength);
            mEntity.update();
            prvTime=0;
          }
          mProgress += (long) msg.obj;

          break;
        case STATE_ITEM_FILE_SIZE:
          Bundle b = msg.getData();
          int itemFileSize=b.getInt(IThreadState.KEY_ITEM_FILE_SIZE,0);
          if(mEntity.getTsCount()>0){
            int unDownSize=(mEntity.getTsCount()-mEntity.getDownTsCount())*itemFileSize;
            mEntity.setDownSize(mEntity.getDownSize()+itemFileSize);
            mEntity.setFileSize(unDownSize+mEntity.getDownSize());
            if(mEntity.getDownTsCount()==3){
              mEntity.setTsItemFileSize(itemFileSize);
            }
            mEntity.update();
          }
          break;
      }
      return false;
    }

    /**
     * 设置进度
     */
    private void handlerPercent() {
      int completeNum = mTaskWrapper.asM3U8().getCompleteNum();
      completeNum++;
      mTaskWrapper.asM3U8().setCompleteNum(completeNum);
      int percent = completeNum * 100 / mTaskRecord.threadRecords.size();
      mEntity.setPercent(percent);
      mEntity.setTsCount(mTaskRecord.threadRecords.size());
      mEntity.setDownTsCount(completeNum);
      mEntity.update();
    }

    @Override public boolean isStop() {
      printInfo("isStop");
      return mStopNum == mFlagQueue.size();
    }

    @Override public boolean isFail() {
      printInfo("isFail");
      return mStartThreadNum == mStopNum + mFailNum + mCompleteNum + mCancelNum;
    }

    @Override public boolean isComplete() {
      printInfo("isComplete");
      return mStartThreadNum == mCompleteNum;
    }

    @Override public boolean isCancel() {
      printInfo("isCancel");
      return mCancelNum == mFlagQueue.size();
    }

    @Override public long getCurrentProgress() {
      return mProgress;
    }

    private void printInfo(String tag) {
      if (false) {
        ALog.d(tag, String.format(
            "startThreadNum = %s, stopNum = %s, cancelNum = %s, failNum = %s, completeNum = %s",
            mStartThreadNum, mStopNum, mCancelNum, mFailNum, mCompleteNum));
      }
    }

    /**
     * 合并文件
     *
     * @return {@code true} 合并成功，{@code false}合并失败
     */
    private boolean mergeFile() {
      ITsMergeHandler mergeHandler = mTaskWrapper.asM3U8().getMergeHandler();
      String cacheDir = getCacheDir();
      List<String> partPath = new ArrayList<>();
      for (ThreadRecord tr : mTaskRecord.threadRecords) {
        partPath.add(getTsFilePath(cacheDir, tr.threadId));
      }
      boolean isSuccess;
      if (mergeHandler != null) {
        isSuccess = mergeHandler.merge(mTaskWrapper.asM3U8().getKeyInfo(), partPath);
        if (mergeHandler.getClass().isAnonymousClass()) {
          mTaskWrapper.asM3U8().setMergeHandler(null);
        }
      } else {
        isSuccess = FileUtil.mergeFile(mTaskRecord.filePath, partPath);
      }
      if (isSuccess) {
        // 合并成功，删除缓存文件
        for (String pp : partPath) {
          File f = new File(pp);
          if (f.exists()) {
            f.delete();
          }
        }
        File cDir = new File(cacheDir);
        if (cDir.exists()) {
          cDir.delete();
        }
        return true;
      } else {
        ALog.e(TAG, "合并失败");
        return false;
      }
    }
  }
}
