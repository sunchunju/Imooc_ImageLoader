package imooc.com.imooc_imageloader.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.view.ViewGroup;
import android.widget.ImageView;

import java.util.LinkedList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * 图片加载类,单例模式
 * Created by suncj1 on 2015/9/21.
 */
public class ImageLoader {
    private static ImageLoader mInstance; //使用单例模式,实例只有一个

    /**
     * 图片缓存的核心对象
     */
    private LruCache<String, Bitmap> mLruCache;

    /**
     * 线程池
     */
    private ExecutorService mThreadPool;
    private static final int DEFAULT_THREAD_COUNT = 1;
    /**
     * 队列的调度方式
     */
    private Type mType = Type.LIFO;

    /**
     * 任务队列
     */
    private LinkedList<Runnable> mTaskQueue;

    /**
     * 后台轮询线程
     * mPoolThreadHandler 用来发送后台轮询message
     */
    private Thread mPoolThread;
    private Handler mPoolThreadHandler;

    /**
     * UI线程的Handler，用于更新图片
     */
    private Handler mUIHandler;

    /**
     * 同步信号量
     */
    private Semaphore mSemaphorePoolThreadHandler = new Semaphore(0);
    private Semaphore mSemaphoreThreadPool;


    public enum Type
    {
        FIFO, LIFO;
    }
    /**
     * 采用private构造方法，因此外界没办法new
     */
    private ImageLoader(int threadCount, Type type){
        init(threadCount, type);
    }

    /**
     * 初始化
     * @param threadCount
     * @param type
     */
    private void init(int threadCount, Type type) {

        //后台轮询线程：通过handler + looper + message来实现
        mPoolThread = new Thread(){
            @Override
            public void run() {

                Looper.prepare();
                mPoolThreadHandler = new Handler(){
                    @Override
                    public void handleMessage(Message msg) {

                        //线程池去取出一个任务进行执行
                        mThreadPool.execute(getTask());
                        try {
                            mSemaphoreThreadPool.acquire();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                };
                //释放一个信号量
                mSemaphorePoolThreadHandler.release();
                Looper.loop();
            }
        };

        mPoolThread.start();

        //获取我们应用的最大可用内存
        int maxMemory = (int) Runtime.getRuntime().maxMemory();
        int cacheMemory = maxMemory / 8;
        mLruCache = new LruCache<String, Bitmap>(cacheMemory){
            @Override
            protected int sizeOf(String key, Bitmap value) {
                //测量每个Bitmap所占据的内存
                return value.getRowBytes()*value.getHeight();
            }
        };

        //创建线程池
        mThreadPool = Executors.newFixedThreadPool(threadCount);
        //创建任务队列
        mTaskQueue = new LinkedList<Runnable>();
        mType = type;

        mSemaphoreThreadPool = new Semaphore(threadCount);
    }

    /**
     * 从任务队列取出一个方法
     * @return
     */
    private Runnable getTask() {
        if (mType == Type.FIFO){
            return mTaskQueue.removeFirst();
        }else if (mType == Type.LIFO){
            return  mTaskQueue.removeLast();
        }
        return null;
    }

    /**
     * 由于构造方法为private，因此外界只能通过类名.方法名获得实例
     * @return
     * @param i
     * @param lifo
     */
    public static ImageLoader getInstance(int i, Type lifo){
        //两重判断：为了提高效率
        if (mInstance == null){  //先判断是否为空，若是空，则需要创建实例，否则不需要创建实例，此层过滤到了大部分的代码
            synchronized (ImageLoader.class){  /*因为上步未做同步处理，所以有可能有多个线程进入此步，这时做同步处理，
            （当然此时需要同步处理的线程就比较少了，提高了效率）线程就只能一个一个往下*/
                if (mInstance == null){ /*排队进来的线程需要再次判断mInstance是否为空
                比如：synchronized之前进来了两个线程A和B，A先执行if (mInstance == null)判断，且创建了一个实例
                然后B再接着判断if (mInstance == null)时，就不需要再次创建实例了*/
                    mInstance = new ImageLoader(DEFAULT_THREAD_COUNT, Type.LIFO);
                }
            }
        }

        return mInstance;
    }


    /**
     *根据path为imageView设置图片
     * @param path
     * @param imageView
     */
    public void loadImage(final String path, final ImageView imageView){

        imageView.setTag(path);  //防止imageView复用多次造成混乱，所以设置path

        if (mUIHandler == null){
            mUIHandler = new Handler(){
                @Override
                public void handleMessage(Message msg) {
                    //获取得到图片，为imageView回调设置图片
                    ImgBeanHolder holder = (ImgBeanHolder) msg.obj;
                    Bitmap bm = holder.bitmap;
                    ImageView imageView1 = holder.imageView;
                    String path = holder.path;

                    //将path与getTag存储路径进行比较
                    if (imageView1.getTag().toString().equals(path)){

                        imageView1.setImageBitmap(bm);
                    }

                }
            };
        }

        //根据path在缓存中获取bitmap
        Bitmap bm = getBitmapFromLruCache(path);

        if (bm != null){
            refreashBitmap(bm, imageView, path);
        }else{

            addTask(new Runnable() {
                @Override
                public void run() {
                    //加载图片
                    //图片的压缩
                    //1.获得图片需要显示的大小
                    ImageSize imageSize = getImageViewSize(imageView);
                    //2. 压缩图片
                    Bitmap bm = decodeSampledBitmapFromPath(path, imageSize.width, imageSize.height);
                    //3. 把图片加入到缓存
                    addBitmapToLruCache(path, bm);
                    //4. 回调
                    refreashBitmap(bm, imageView, path);

                    mSemaphoreThreadPool.release();
                }
            });
        }
    }

    private void refreashBitmap(Bitmap bm, ImageView imageView, String path) {
        Message message = Message.obtain();
        ImgBeanHolder holder = new ImgBeanHolder();
        holder.bitmap = bm;
        holder.imageView = imageView;
        holder.path = path;
        message.obj = holder;
        mUIHandler.sendMessage(message);
    }

    /**
     * 将图片加入LruCache
     * @param path
     * @param bm
     */
    private void addBitmapToLruCache(String path, Bitmap bm) {

        if (getBitmapFromLruCache(path) == null){
            if (bm != null){
                mLruCache.put(path, bm);
            }
        }
    }

    /**
     * 根据图片需要显示的宽和高对图片进行压缩
     * @param path
     * @param width: 需求的宽
     * @param height： 需求的高
     * @return
     */
    private Bitmap decodeSampledBitmapFromPath(String path, int width, int height) {

        //获得图片的宽和高，并不把图片加载到内存中
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, options);

        options.inSampleSize = caculateInSampleSize(options, width, height);

        //使用获取到的InSampleSize再次解析图片
        options.inJustDecodeBounds = false;
        Bitmap bitmap = BitmapFactory.decodeFile(path, options);
        return bitmap;
    }

    /**
     * 根据需求的宽和高以及图片实际的宽和高计算SampleSize
     * @param options
     * @param reqWidth
     * @param reqHeight
     * @return
     */
    private int caculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        int width = options.outWidth;
        int height = options.outHeight;

        int inSampleSize = 1;

        if (width > reqWidth || height > reqHeight){
            int widthRadio = Math.round(width*1.0f/reqWidth);
            int heightRadio = Math.round(height*1.0f/reqHeight);

            inSampleSize = Math.max(widthRadio, heightRadio);
        }
        return inSampleSize;
    }


    /**
     * 根据imageView获得适当的压缩的宽和高
     * @param imageView
     * @return
     */
    private ImageSize getImageViewSize(ImageView imageView) {

        ImageSize imageSize = new ImageSize();

        DisplayMetrics displayMetrics = imageView.getContext().getResources().getDisplayMetrics();
        ViewGroup.LayoutParams lp = imageView.getLayoutParams();
        int width = imageView.getWidth(); //获取imageView的实际宽度
        if (width <= 0){

            width = lp.width; //获取imageView在layout中声明的宽度
        }
        if (width <= 0){
            width = imageView.getMaxWidth(); //检查最大值
        }
        if (width <= 0){
            width = displayMetrics.widthPixels; //获取屏幕宽度
        }

        int height = imageView.getHeight(); //获取imageView的实际宽度
        if (height <= 0){

            height = lp.height; //获取imageView在layout中声明的宽度
        }
        if (height <= 0){
            height = imageView.getMaxHeight(); //检查最大值
        }
        if (height <= 0){
            height = displayMetrics.heightPixels; //获取屏幕宽度
        }

        imageSize.height = height;
        imageSize.width = width;

        return imageSize;
    }

    private synchronized void addTask(Runnable runnable) {
        mTaskQueue.add(runnable);

        //if(mPoolThreadHandler == null) wait

        try {
            if (mPoolThreadHandler == null)
                mSemaphorePoolThreadHandler.acquire();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        mPoolThreadHandler.sendEmptyMessage(0x110);
    }

    /**
     * 根据path在缓存中获取bitmap
     * @param path
     * @return
     */
    private Bitmap getBitmapFromLruCache(String path) {
        return mLruCache.get(path);
    }

    private class ImgBeanHolder{
        Bitmap bitmap;
        ImageView imageView;
        String path;
    }

    private class ImageSize{

        int width;
        int height;
    }
}
