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
package com.arialyy.aria.core.upload;

import android.os.Handler;
import com.arialyy.aria.core.common.BaseListener;
import com.arialyy.aria.core.common.RecordHandler;
import com.arialyy.aria.core.inf.IEntity;
import com.arialyy.aria.core.inf.IUploadListener;
import com.arialyy.aria.core.inf.TaskSchedulerType;
import com.arialyy.aria.util.RecordUtil;

/**
 * 下载监听类
 */
class BaseUListener extends BaseListener<UploadEntity, UTaskWrapper, UploadTask>
    implements IUploadListener {

  BaseUListener(UploadTask task, Handler outHandler) {
    super(task, outHandler);
  }

  @Override protected void handleCancel() {
    int sType = getTask().getSchedulerType();
    if (sType == TaskSchedulerType.TYPE_CANCEL_AND_NOT_NOTIFY) {
      mEntity.setComplete(false);
      mEntity.setState(IEntity.STATE_WAIT);
      RecordUtil.delTaskRecord(mEntity.getFilePath(), RecordHandler.TYPE_UPLOAD,
          mTaskWrapper.isRemoveFile(), false);
    } else {
      RecordUtil.delTaskRecord(mEntity.getFilePath(), RecordHandler.TYPE_UPLOAD,
          mTaskWrapper.isRemoveFile(), true);
    }
  }
}