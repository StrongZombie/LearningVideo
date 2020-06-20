package com.cxp.learningvideo.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.nio.ByteBuffer


/**
 * 解码器基类
 *
 * @author Chen Xiaoping (562818444@qq.com)
 * @since LearningVideo
 * @version LearningVideo
 * @Datetime 2019-09-02 09:43
 *
 */
abstract class BaseDecoder(private val mFilePath: String): IDecoder {

    private val TAG = "BaseDecoder"

    //-------------线程相关------------------------
    /**
     * 解码器是否在运行
     */
    private var mIsRunning = true

    /**
     * 线程等待锁
     */
    private val mLock = Object()

    /**
     * 是否可以进入解码
     */
    private var mReadyForDecode = false

    //---------------状态相关-----------------------
    /**
     * 音视频解码器
     */
    private var mCodec: MediaCodec? = null

    /**
     * 音视频数据读取器
     */
    private var mExtractor: IExtractor? = null

    /**
     * 解码输入缓存区
     */
    private var mInputBuffers: Array<ByteBuffer>? = null

    /**
     * 解码输出缓存区
     */
    private var mOutputBuffers: Array<ByteBuffer>? = null

    /**
     * 解码数据信息
     */
    private var mBufferInfo = MediaCodec.BufferInfo()

    private var mState = DecodeState.STOP

    protected var mStateListener: IDecoderStateListener? = null

    /**
     * 流数据是否结束
     */
    private var mIsEOS = false

    protected var mVideoWidth = 0

    protected var mVideoHeight = 0

    private var mDuration: Long = 0

    private var mStartPos: Long = 0

    private var mEndPos: Long = 0

    /**
     * 开始解码时间，用于音视频同步
     */
    private var mStartTimeForSync = -1L

    // 是否需要音视频渲染同步
    private var mSyncRender = true

    final override fun run() {
        if (mState == DecodeState.STOP) {
            mState = DecodeState.START
        }
        mStateListener?.decoderPrepare(this)

        //【解码步骤：1. 初始化，并启动解码器】
        if (!init()) return

        Log.i(TAG, "开始解码")
        try {
            while (mIsRunning) {
                if (mState != DecodeState.START &&
                    mState != DecodeState.DECODING &&
                    mState != DecodeState.SEEKING) {
                    Log.i(TAG, "进入等待：$mState")

                    waitDecode()

                    // ---------【同步时间矫正】-------------
                    //恢复同步的起始时间，即去除等待流失的时间
                    mStartTimeForSync = System.currentTimeMillis() - getCurTimeStamp()
                }

                if (!mIsRunning ||
                    mState == DecodeState.STOP) {
                    mIsRunning = false
                    break
                }

                if (mStartTimeForSync == -1L) {
                    mStartTimeForSync = System.currentTimeMillis()
                }

                //如果数据没有解码完毕，将数据推入解码器解码
                if (!mIsEOS) {
                    //【解码步骤：2. 见数据压入解码器输入缓冲】
                    /**
                     * 步骤2 把数据压人到解码器输入缓冲（ByteBuffer）返回Index
                     * mCodec!!.dequeueInputBuffer(2000) 2000是查询是否可用等待的时间
                     * 返回的是索引
                     * */
                    mIsEOS = pushBufferToDecoder()
                }

                //【解码步骤：3. 将解码好的数据从缓冲区拉取出来】
                /**
                 * 解码过程交给MediaCodec，把解码好的数据拉出来（根据压人的时候的索引）
                 * 交友给Extractor 提前解码器数据，（Extractor为读取数据的类）
                 * 填充到缓冲区周年
                 * */
                val index = pullBufferFromDecoder()
                if (index >= 0) {
                    // ---------【音视频同步】-------------
                    if (mSyncRender && mState == DecodeState.DECODING) {
                        sleepRender()
                    }
                    //【解码步骤：4. 渲染】
                    /**
                     * 步骤四，渲染，由子类来渲，不同类不同渲染方法
                     * */
                    if (mSyncRender) {// 如果只是用于编码合成新视频，无需渲染
                        render(mOutputBuffers!![index], mBufferInfo)
                    }
                    /**
                     * 解码好的数据传出来
                     * */
                    //将解码数据传递出去
                    val frame = Frame()
                    frame.buffer = mOutputBuffers!![index]
                    frame.setBufferInfo(mBufferInfo)
                    mStateListener?.decodeOneFrame(this, frame)

                    //【解码步骤：5. 释放输出缓冲】
                    /**
                     * 步骤五释放，可以重新压人数据
                     * 第二个参数这个参数在视频解码时，用于决定是否要将这一帧数据显示出来。
                     * */
                    mCodec!!.releaseOutputBuffer(index, true)

                    if (mState == DecodeState.START) {
                        mState = DecodeState.PAUSE
                    }
                }
                //【解码步骤：6. 判断解码是否完成】
                /**
                 * 拿到的数据是否是结束标志
                 * 释放解码器
                 * */
                if (mBufferInfo.flags == MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
                    Log.i(TAG, "解码结束")
                    mState = DecodeState.FINISH
                    mStateListener?.decoderFinish(this)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            doneDecode()
            release()
        }
    }

    private fun init(): Boolean {
        if (mFilePath.isEmpty() || !File(mFilePath).exists()) {
            Log.w(TAG, "文件路径为空")
            mStateListener?.decoderError(this, "文件路径为空")
            return false
        }

        if (!check()) return false

        //初始化数据提取器
        mExtractor = initExtractor(mFilePath)
        if (mExtractor == null ||
            mExtractor!!.getFormat() == null) {
            Log.w(TAG, "无法解析文件")
            return false
        }

        //初始化参数
        if (!initParams()) return false

        //初始化渲染器
        if (!initRender()) return false

        //初始化解码器
        if (!initCodec()) return false
        return true
    }

    private fun initParams(): Boolean {
        try {
            val format = mExtractor!!.getFormat()!!
            mDuration = format.getLong(MediaFormat.KEY_DURATION) / 1000
            if (mEndPos == 0L) mEndPos = mDuration

            initSpecParams(mExtractor!!.getFormat()!!)
        } catch (e: Exception) {
            return false
        }
        return true
    }

    private fun initCodec(): Boolean {
        try {
            //根据视频编码格式初始化解码器，初始化MediaCodec
            /**
             * 步骤一
             * 初始化步骤都是
             * 根据格式获取格式对应的类别
             * 在mediacode 周年查找这个type对应的解码器
             * 最后，创建解码器
             * 格式-》type ——》MediaCodec
             * */
            val type = mExtractor!!.getFormat()!!.getString(MediaFormat.KEY_MIME)
            mCodec = MediaCodec.createDecoderByType(type)

            //配置解码器
            if (!configCodec(mCodec!!, mExtractor!!.getFormat()!!)) {
                waitDecode()
            }
            //启动解码器
            mCodec!!.start()

            //获取输入输出缓冲区
            mInputBuffers = mCodec?.inputBuffers
            mOutputBuffers = mCodec?.outputBuffers
        } catch (e: Exception) {
            return false
        }
        return true
    }

    /**
     * 步骤2
     * 把数据压入解码栈中
     * */
    private fun pushBufferToDecoder(): Boolean {
        /**
         * 查询可用的缓冲区索引，每两秒 ， 在可用的数据缓冲区压人未解码数据，解码后当数据缓冲区成功渲染
         * 又变成可用用的数据缓冲区
         * */
        var inputBufferIndex = mCodec!!.dequeueInputBuffer(1000)
        var isEndOfStream = false


        if (inputBufferIndex >= 0) {
            /**
             *  获取mediacode的输入缓冲区
             *  mCodec?.inputBuffers
             *  拿到他对应的索引的buffer
             *  通过Extra读取文件数据，压入到缓冲区里
             * */
            val inputBuffer = mInputBuffers!![inputBufferIndex]
            val sampleSize = mExtractor!!.readBuffer(inputBuffer)

            /**
             * 调用 queueInoputBuffer 提交数据，没有数据时提交结束标志
             * */
            if (sampleSize < 0) {
                //如果数据已经取完，压入数据结束标志：MediaCodec.BUFFER_FLAG_END_OF_STREAM
                mCodec!!.queueInputBuffer(inputBufferIndex, 0, 0,
                    0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                isEndOfStream = true
                //结束
            } else {

                mCodec!!.queueInputBuffer(inputBufferIndex, 0,
                    sampleSize, mExtractor!!.getCurrentTimestamp(), 0)
            }
        }
        return isEndOfStream
    }

    /**
     * 查询是否有可以用的数据（解码好的）缓冲区可以去渲染的
     * */
    private fun pullBufferFromDecoder(): Int {
        // 查询是否有解码完成的数据，index >=0 时，表示数据有效，并且index为缓冲区索引
        /**
         *1000为等待时间
         * MediaCodec.INFO_OUTPUT_FORMAT_CHANGED：输出格式改变了
         * MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED：输入缓冲改变了
         * MediaCodec.INFO_TRY_AGAIN_LATER：没有可用数据，等会再来
         * 大于等于0：有可用数据，index就是输出缓冲索引
         * */
        var index = mCodec!!.dequeueOutputBuffer(mBufferInfo, 1000)
        when (index) {
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {}
            MediaCodec.INFO_TRY_AGAIN_LATER -> {}
            MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                mOutputBuffers = mCodec!!.outputBuffers
            }
            else -> {
                return index
            }
        }
        return -1
    }

    private fun sleepRender() {
        val passTime = System.currentTimeMillis() - mStartTimeForSync
        val curTime = getCurTimeStamp()
        if (curTime > passTime) {
            Thread.sleep(curTime - passTime)
        }
    }

    private fun release() {
        try {
            Log.i(TAG, "解码停止，释放解码器")
            mState = DecodeState.STOP
            mIsEOS = false
            mExtractor?.stop()
            mCodec?.stop()
            mCodec?.release()
            mStateListener?.decoderDestroy(this)
        } catch (e: Exception) {
        }
    }

    /**
     * 解码线程进入等待
     */
    private fun waitDecode() {
        try {
            if (mState == DecodeState.PAUSE) {
                mStateListener?.decoderPause(this)
            }
            synchronized(mLock) {
                mLock.wait()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 通知解码线程继续运行
     */
    protected fun notifyDecode() {
        synchronized(mLock) {
            mLock.notifyAll()
        }
        if (mState == DecodeState.DECODING) {
            mStateListener?.decoderRunning(this)
        }
    }

    override fun pause() {
        mState = DecodeState.DECODING
    }

    override fun goOn() {
        mState = DecodeState.DECODING
        notifyDecode()
    }

    override fun seekTo(pos: Long): Long {
        return 0
    }

    override fun seekAndPlay(pos: Long): Long {
        return 0
    }

    override fun stop() {
        mState = DecodeState.STOP
        mIsRunning = false
        notifyDecode()
    }

    override fun isDecoding(): Boolean {
        return mState == DecodeState.DECODING
    }

    override fun isSeeking(): Boolean {
        return mState == DecodeState.SEEKING
    }

    override fun isStop(): Boolean {
        return mState == DecodeState.STOP
    }

    override fun setSizeListener(l: IDecoderProgress) {
    }

    override fun setStateListener(l: IDecoderStateListener?) {
        mStateListener = l
    }

    override fun getWidth(): Int {
        return mVideoWidth
    }

    override fun getHeight(): Int {
        return mVideoHeight
    }

    override fun getDuration(): Long {
        return mDuration
    }

    override fun getCurTimeStamp(): Long {
        return mBufferInfo.presentationTimeUs / 1000
    }

    override fun getRotationAngle(): Int {
        return 0
    }

    override fun getMediaFormat(): MediaFormat? {
        return mExtractor?.getFormat()
    }

    override fun getTrack(): Int {
        return 0
    }

    override fun getFilePath(): String {
        return mFilePath
    }

    override fun withoutSync(): IDecoder {
        mSyncRender = false
        return this
    }

    /**
     * 检查子类参数
     */
    abstract fun check(): Boolean

    /**
     * 初始化数据提取器
     */
    abstract fun initExtractor(path: String): IExtractor

    /**
     * 初始化子类自己特有的参数
     */
    abstract fun initSpecParams(format: MediaFormat)

    /**
     * 配置解码器
     */
    abstract fun configCodec(codec: MediaCodec, format: MediaFormat): Boolean

    /**
     * 初始化渲染器
     */
    abstract fun initRender(): Boolean

    /**
     * 渲染
     */
    abstract fun render(outputBuffer: ByteBuffer,
                        bufferInfo: MediaCodec.BufferInfo)

    /**
     * 结束解码
     */
    abstract fun doneDecode()
}