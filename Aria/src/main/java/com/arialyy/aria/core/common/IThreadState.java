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

import android.os.Handler;

/**
 * 线程任务状态
 */
public interface IThreadState extends Handler.Callback {
  int STATE_STOP = 0x01;
  int STATE_FAIL = 0x02;
  int STATE_CANCEL = 0x03;
  int STATE_COMPLETE = 0x04;
  int STATE_RUNNING = 0x05;
  int STATE_UPDATE_PROGRESS = 0x06;
  int STATE_ITEM_FILE_SIZE = 0x09;
  String KEY_RETRY = "KEY_RETRY";
  String KEY_ERROR_INFO = "KEY_ERROR_INFO";
  String KEY_ITEM_FILE_SIZE = "KEY_ITEM_FILE_SIZE";
  /**
   * 任务是否已经停止
   *
   * @return true 任务已停止
   */
  boolean isStop();

  /**
   * 任务是否已经失败
   *
   * @return true 任务已失败
   */
  boolean isFail();

  /**
   * 任务是否已经完成
   *
   * @return true 任务已完成
   */
  boolean isComplete();

  /**
   * 任务是否已经取消
   *
   * @return true 任务已取消
   */
  boolean isCancel();

  /**
   * 获取当前任务进度
   *
   * @return 任务当前进度
   */
  long getCurrentProgress();
}
