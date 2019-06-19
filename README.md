
# Aria
![图标](https://github.com/AriaLyy/DownloadUtil/blob/v_3.0/app/src/main/res/mipmap-hdpi/ic_launcher.png)</br>
## [ENGLISH DOC](https://github.com/AriaLyy/Aria/blob/master/ENGLISH_README.md)</br>
### 2019/06/19更新说明
#### 1、优化m3u8下载
比如一个M3U8的url是：http://xxxx.com/2019/07/06/index.m3u8<br/>
文件内容如下：
```
#EXTM3U
#EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=1500000,RESOLUTION=1920x1080
1500kb/hls/index.m3u8
```
我们需要在上面内容中的1500kb/hls/index.m3u8文件中来获取ts列表。所以就需要1500kb/hls/index.m3u8和域名拼接。在实际项目中发现拼接域名的方式有时候是 http://xxxx.com/1500kb/hls/index.m3u8 这样的。有时候又是 http://xxxx.com/2019/07/06/1500kb/hls/index.m3u8 这样的。此次更新会从这两种方式中自动获取正确的拼接方式。
#### 2、优化M3U8下载速度
在下载M3U8时发现获取下载速度task.getmEntity().getSpeed()一直是0。于是添加了一个新的下载速度属性。task.getmEntity().getSpeed2()即可获取。
计算方式为:
```
mProgress-上一秒的mProgress
```
### 3、新增M3U8视频文件大小。
DownloadEntity实体类新增了4个属性：
```
private int tsCount=0;//ts文件总数
  private int downTsCount=0;//已下载的ts数量
  private int tsItemFileSize=0;//第三个TS文件的大小
  private int downSize=0;//已下载的大小。原来的CurrentProgress在暂停又重新下载后就会恢复为0.所以加了这个字段。
```
task.getmEntity().getFileSize()获取视频大小<br/>
因为M3U8文件的特殊性。在下载过程中很难获取到M3U8视频的实际大小。获取到的视频文件大小也只是一个大概值。<br/>
视频文件大小的计算方式为：
```
downSize+最新一个ts文件的大小*(tsCount-downTsCount)
```
因此task.getmEntity().getFileSize()获取到的值会一直在变化。但是变化的大小都是在视频实际大小左右变化<br/>
如果你需要一个不会一直变化的大小可以用
```
tsItemFileSize*tsCount
```
的方式来获取。这样也是一个大概值。用第三个TS的大小乘以TS总数。



## [中文文档](https://aria.laoyuyu.me/aria_doc)
Aria项目源于工作中遇到的一个文件下载管理的需求，当时被下载折磨的痛不欲生，从那时起便萌生了编写一个简单易用，稳当高效的下载框架，aria经历了1.0到3.0的开发，算是越来越接近当初所制定的目标了。

Aria有以下特点：
 + 简单、方便
   - 可以在Activity、Service、Fragment、Dialog、popupWindow、Notification等组件中使用
   - 支持HTTP\FTP断点续传下载、多任务自动调度
   - 支持多文件打包下载，多文件共享同一进度（如：视频 + 封面 + 字幕）
   - 支持下载FTP文件夹
   - 支持HTTP表单上传
   - 支持文件FTP断点续传上传
   - 支持FTPS/SFTP断点续传，[see](https://aria.laoyuyu.me/aria_doc/download/ftps.html)
 + 支持https地址下载
   - 在配置文件中很容易就可以设置CA证书的信息
 + 支持[多线程分块下载](https://aria.laoyuyu.me/aria_doc/start/config.html)，能更有效的发挥机器IO性能
 + 支持300、301、302重定向下载链接下载
 + 下载支持文件长度动态增加，文件下载初始化时将不再占用过多的内存空间，见[动态长度配置](https://aria.laoyuyu.me/aria_doc/start/config.html#%E4%B8%8B%E8%BD%BD%E5%8A%A8%E6%80%81%E6%96%87%E4%BB%B6%E8%AF%B4%E6%98%8E)

[怎样使用Aria?](#使用)

如果你觉得Aria对你有帮助，您的star和issues将是对我最大支持.`^_^`

## 示例
* 多任务下载

![多任务下载](https://github.com/AriaLyy/DownloadUtil/blob/master/img/download_img.gif)

* 速度限制

![网速下载限制](https://github.com/AriaLyy/DownloadUtil/blob/master/img/max_speed.gif)

* 多文件打包下载

<img src="https://github.com/AriaLyy/DownloadUtil/blob/master/img/group_task.gif" width="360" height="640"/>


## 引入库
[![Core](https://api.bintray.com/packages/arialyy/maven/AriaApi/images/download.svg)](https://bintray.com/arialyy/maven/AriaApi/_latestVersion)
[![Compiler](https://api.bintray.com/packages/arialyy/maven/AriaCompiler/images/download.svg)](https://bintray.com/arialyy/maven/AriaCompiler/_latestVersion)

```java
compile 'com.arialyy.aria:aria-core:3.6.4'
annotationProcessor 'com.arialyy.aria:aria-compiler:3.6.4'
```
如果出现android support依赖错误，请将 `compile 'com.arialyy.aria:aria-core:<last-version>'`替换为
```
api('com.arialyy.aria:aria-core:<last-version>'){
   exclude group: 'com.android.support'
}
```
如果你使用的是kotlin，请使用kotlin官方提供的方法配置apt，[kotlin kapt官方配置传送门](https://www.kotlincn.net/docs/reference/kapt.html)

__注意：3.5.4以下版本升级时，需要更新[配置文件]！！(https://aria.laoyuyu.me/aria_doc/start/config.html)__

***
## 使用
由于Aria涉及到文件和网络的操作，因此需要你在manifest文件中添加以下权限，如果你希望在6.0以上的系统中使用Aria，那么你需要动态向安卓系统申请文件系统读写权限，[如何使用安卓系统权限](https://developer.android.com/training/permissions/index.html?hl=zh-cn)
```xml
<uses-permission android:name="android.permission.MOUNT_UNMOUNT_FILESYSTEMS"/>
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"/>
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
```

## 使用Aria
### 基本使用
例子为单任务下载，只需要很简单的代码，便可以实现下载功能
  ```java
  Aria.download(this)
      .load(DOWNLOAD_URL)     //读取下载地址
      .setFilePath(DOWNLOAD_PATH) //设置文件保存的完整路径
      .start();   //启动下载
  ```

### 任务状态的获取
基于解耦合的考虑，Aria的下载功能是和状态获取相分离的，状态的获取并不会集成到链式代码中，但是Aria提供了另一种更简单更灵活的方案。
通过注解，你可以很容易获取任务的所有状态。

1. 将对象注册到Aria
```java
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    Aria.download(this).register();
}
```

2. 通过注解获取任务执行状态
 **注意：**
 - 注解回掉采用Apt的方式实现，所以，你不需要担心这会影响你机器的性能
 - 被注解的方法**不能被private修饰**
 - 被注解的方法**只能有一个参数，并且参数类型必须是`DownloadTask`或`UploadTask`或`DownloadGroupTask`**
 - 方法名可以为任意字符串
 
```java
//在这里处理任务执行中的状态，如进度进度条的刷新
@Download.onTaskRunning protected void running(DownloadTask task) {
	if(task.getKey().eques(url)){
		....
		可以通过url判断是否是指定任务的回调
	}
	int p = task.getPercent();	//任务进度百分比
    String speed = task.getConvertSpeed();	//转换单位后的下载速度，单位转换需要在配置文件中打开
   	String speed1 = task.getSpeed(); //原始byte长度速度
}

@Download.onTaskComplete void taskComplete(DownloadTask task) {
	//在这里处理任务完成的状态
}
```


### 版本日志
 + v_3.6.4 (2019/5/16)
    - 优化任务接收器的代码结构
    - 修复`DbEntity.saveAll()`失败的问题
    - 修复分块任务重命名失败的问题
    - fix bug https://github.com/AriaLyy/Aria/issues/379
    - 移除`getDownloadTask(String url)`、`getGroupTask(List<String> urls)`、`getFtpDirTask(String path)`
      等获取任务的api，如果你希望获取对应状态，请使用实体的状态判断，如：`getDownloadEntity()`、`getDownloadGroupEntity()`
      `getFtpDirEntity()`
    - fix bug https://github.com/AriaLyy/Aria/issues/388
    - 修复使用`Content-Disposition`的文件名时，第一次下载无法重命名文件的问题
    - 修复使用`Content-Disposition`的文件名时，多次重命名文件的问题
    - 组合任务新增`unknownSize()`，用于处理组合任务大小ø未知的情况，https://github.com/AriaLyy/Aria/issues/380
    - 优化`AbsThreadTask`代码
    - 新增文件长度处理功能 https://github.com/AriaLyy/Aria/issues/393
      ```java
      .setFileLenAdapter(new IHttpFileLenAdapter() {
        @Override public long handleFileLen(Map<String, List<String>> headers) {
          ...
          // 处理header中的文件长度

          return fileLen;
        }
       })
      ```
    - 修复组合任务多次回调`onStop`注解的问题
    - 优化`isRunning()`的逻辑，任务是否在执行的判断将更加准确
    - 修复多次重复快速点击`暂停、开始`时，任务有可能重复下载的问题
    - 修复组合任务中没有等待中的只任务实体保存失败的问题
    - 新增组合任务url重复检查 https://github.com/AriaLyy/Aria/issues/395
    - 初始化任务时，如果url、path有错误将会回调`@Download.onTaskFail`、`@Upload.onTaskFail`、`@DownGroup.onTaskFail`

[更多版本记录](https://github.com/AriaLyy/Aria/blob/master/DEV_LOG.md)

## 混淆配置
```
-dontwarn com.arialyy.aria.**
-keep class com.arialyy.aria.**{*;}
-keep class **$$DownloadListenerProxy{ *; }
-keep class **$$UploadListenerProxy{ *; }
-keep class **$$DownloadGroupListenerProxy{ *; }
-keepclasseswithmembernames class * {
    @Download.* <methods>;
    @Upload.* <methods>;
    @DownloadGroup.* <methods>;
}

```

## 其他
 有任何问题，可以在[issues](https://github.com/AriaLyy/Aria/issues)给我留言反馈。</br>
 在提交问题前，希望你已经查看过[wiki](https://aria.laoyuyu.me/aria_doc/)或搜索过[issues](https://github.com/AriaLyy/Aria/issues)。</br>
 交流群：524329160

***

License
-------

    Copyright 2016 AriaLyy(https://github.com/AriaLyy/Aria)

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.










