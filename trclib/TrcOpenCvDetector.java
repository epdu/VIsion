/*
 * Copyright (c) 2022 Titan Robotics Club (http://www.titanrobotics.com)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package TrcCommonLib.trclib;

import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

/**
 * This class implements a generic OpenCV detector. Typically, it is extended by a specific detector that provides
 * the pipeline to process an image for detecting objects using OpenCV APIs.
 */
public abstract class TrcOpenCvDetector implements TrcVisionProcessor<Mat, TrcOpenCvDetector.DetectedObject<?>>
{
    /**
     * This class encapsulates info of the detected object. It extends TrcVisionTargetInfo.ObjectInfo that requires
     * it to provide a method to return the detected object rect.
     */
    public static abstract class DetectedObject<O> implements TrcVisionTargetInfo.ObjectInfo
    {
        public final O object;

        /**
         * Constructor: Creates an instance of the object.
         *
         * @param object specifies the contour of the object.
         */
        public DetectedObject(O object)
        {
            this.object = object;
        }   //DetectedObject

        /**
         * This method returns the string form of the target info.
         *
         * @return string form of the target info.
         */
        @Override
        public String toString()
        {
            return "Rect=" + getRect() + ",area=" + getArea();
        }   //toString

    }   //class DetectedObject

    /**
     * This interface provides a method for filtering false positive objects in the detected target list.
     */
    public interface FilterTarget
    {
        boolean validateTarget(DetectedObject<?> object);
    }   //interface FilterTarget

    private static final Scalar ANNOTATE_COLOR = new Scalar(0,255,0,255);
    private static final int ANNOTATE_RECT_THICKNESS = 3;

    private final String instanceName;
    private final int imageWidth, imageHeight;
    private final TrcDbgTrace tracer;
    private final TrcHomographyMapper homographyMapper;
    private final TrcVisionTask<Mat, DetectedObject<?>> visionTask;
    private volatile TrcOpenCvPipeline<DetectedObject<?>> openCvPipeline = null;
    private Object pipelineLock = new Object();

    /**
     * Constructor: Create an instance of the object.
     *
     * @param instanceName specifies the instance name.
     * @param numImageBuffers specifies the number of image buffers to allocate.
     * @param imageWidth specifies the width of the camera image.
     * @param imageHeight specifies the height of the camera image.
     * @param cameraRect specifies the camera rectangle for Homography Mapper, can be null if not provided.
     * @param worldRect specifies the world rectangle for Homography Mapper, can be null if not provided.
     * @param tracer specifies the tracer for trace info, null if none provided.
     */
    public TrcOpenCvDetector(
        String instanceName, int numImageBuffers, int imageWidth, int imageHeight,
        TrcHomographyMapper.Rectangle cameraRect, TrcHomographyMapper.Rectangle worldRect,
        TrcDbgTrace tracer)
    {
        this.instanceName = instanceName;
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        this.tracer = tracer;

        if (cameraRect != null && worldRect != null)
        {
            homographyMapper = new TrcHomographyMapper(cameraRect, worldRect);
        }
        else
        {
            homographyMapper = null;
        }
        //
        // Pre-allocate the image buffers.
        //
        Mat[] imageBuffers = new Mat[numImageBuffers];
        for (int i = 0; i < imageBuffers.length; i++)
        {
            imageBuffers[i] = new Mat();
        }

        visionTask = new TrcVisionTask<>(instanceName, this, imageBuffers);
        visionTask.setPerfReportEnabled(tracer);
    }   //TrcOpenCvDetector

    /**
     * This method returns the instance name.
     *
     * @return instance name.
     */
    @Override
    public String toString()
    {
        return instanceName;
    }   //toString

    /**
     * This method enables/disables pipeline processing.
     *
     * @param enabled specifies true to start pipeline processing, false to stop.
     */
    private void setEnabled(boolean enabled)
    {
        synchronized (pipelineLock)
        {
            boolean taskEnabled = visionTask.isTaskEnabled();

            if (enabled && !taskEnabled)
            {
                if (openCvPipeline != null)
                {
                    openCvPipeline.reset();
                    visionTask.setTaskEnabled(true);
                }
            }
            else if (!enabled && taskEnabled)
            {
                visionTask.setTaskEnabled(false);
            }
        }
    }   //setEnabled

    /**
     * This method sets the vision task processing interval.
     *
     * @param interval specifies the processing interval in msec. If 0, process as fast as the CPU can run.
     */
    public void setProcessingInterval(long interval)
    {
        visionTask.setProcessingInterval(interval);
    }   //setProcessingInterval

    /**
     * This method returns the vision task processing interval.
     *
     * @return vision task processing interval in msec.
     */
    public long getProcessingInterval()
    {
        return visionTask.getProcessingInterval();
    }   //getProcessingInterval

    /**
     * This method enables/disables image to be displayed on the output stream and optionally added annotation of
     * the detected object rects on the output stream.
     *
     * @param step specifies the intermediate step frame to be displayed (0 is the original image,
     *        -1 to disable the image display).
     * @param annotate specifies true to annotate the image with the detected object rectangles, false otherwise.
     *        This parameter is ignored if intermediateStep is -1.
     */
    public void setVideoOutEnabled(int step, boolean annotate)
    {
        visionTask.setVideoOutEnabled(step, annotate);
    }   //setVideoOutEnabled

    /**
     * This method sets the OpenCV pipeline to be used for the detection and enables it.
     *
     * @param pipeline specifies the pipeline to be used for detection, can be null to disable vision.
     */
    public void setPipeline(TrcOpenCvPipeline<DetectedObject<?>> pipeline)
    {
        synchronized (pipelineLock)
        {
            if (pipeline != openCvPipeline)
            {
                // Pipeline has changed.
                // Enable vision if setting a new pipeline, disable if setting null pipeline.
                openCvPipeline = pipeline;
                setEnabled(pipeline != null);
            }
        }
    }   //setPipeline

    /**
     * This method returns the current pipeline.
     *
     * @return current pipeline, null if no set pipeline.
     */
    public TrcOpenCvPipeline<DetectedObject<?>> getPipeline()
    {
        return openCvPipeline;
    }   //getPipeline

    /**
     * This method returns an array of detected targets from Grip vision.
     *
     * @param filter specifies the filter to call to filter out false positive targets.
     * @param comparator specifies the comparator to sort the array if provided, can be null if not provided.
     * @param objHeightOffset specifies the object height offset above the floor.
     * @param cameraHeight specifies the height of the camera above the floor.
     * @return array of detected target info.
     */
    @SuppressWarnings("unchecked")
    public TrcVisionTargetInfo<DetectedObject<?>>[] getDetectedTargetsInfo(
        FilterTarget filter, Comparator<? super TrcVisionTargetInfo<DetectedObject<?>>> comparator,
        double objHeightOffset, double cameraHeight)
    {
        final String funcName = instanceName + ".getDetectedTargetsInfo";
        TrcVisionTargetInfo<DetectedObject<?>>[] detectedTargets = null;
        DetectedObject<?>[] objects = visionTask.getDetectedObjects();

        if (objects != null)
        {
            ArrayList<TrcVisionTargetInfo<DetectedObject<?>>> targetList = new ArrayList<>();

            for (DetectedObject<?> obj : objects)
            {
                if (filter == null || filter.validateTarget(obj))
                {
                    TrcVisionTargetInfo<DetectedObject<?>> targetInfo =
                        new TrcVisionTargetInfo<>(
                            obj, imageWidth, imageHeight, homographyMapper, objHeightOffset, cameraHeight);
                    targetList.add(targetInfo);
                }
            }

            if (targetList.size() > 0)
            {
                detectedTargets = targetList.toArray(new TrcVisionTargetInfo[0]);
                if (comparator != null && detectedTargets.length > 1)
                {
                    Arrays.sort(detectedTargets, comparator);
                }
            }

            if (detectedTargets != null && tracer != null)
            {
                for (int i = 0; i < detectedTargets.length; i++)
                {
                    tracer.traceInfo(funcName, "[%d] Target=%s", i, detectedTargets[i]);
                }
            }
        }

        return detectedTargets;
    }   //getDetectedTargetsInfo

    //
    // Implements TrcVisionProcessor interface.
    //

    /**
     * This method is called to detect objects in the acquired image frame.
     *
     * @param image specifies the image to be processed.
     * @return detected objects, null if none detected.
     */
    @Override
    public TrcOpenCvDetector.DetectedObject<?>[] processFrame(Mat image)
    {
        openCvPipeline.process(image);
        return openCvPipeline.getDetectedObjects();
    }   //processFrame

    /**
     * This method is called to overlay rectangles of the detected objects on an image.
     *
     * @param image specifies the frame to be rendered to the video output.
     * @param detectedObjects specifies the detected objects.
     */
    @Override
    public void annotateFrame(Mat image, TrcOpenCvDetector.DetectedObject<?>[] detectedObjects)
    {
        for (TrcOpenCvDetector.DetectedObject<?> object : detectedObjects)
        {
            Rect rect = object.getRect();
            Imgproc.rectangle(image, rect, ANNOTATE_COLOR, ANNOTATE_RECT_THICKNESS);
        }
    }   //annotatedFrame

    /**
     * This method returns an intermediate processed frame. Typically, a pipeline processes a frame in a number of
     * steps. It may be useful to see an intermediate frame for a step in the pipeline for tuning or debugging
     * purposes.
     *
     * @param step specifies the intermediate step (step 0 is the original input frame).
     * @return processed frame of the specified step.
     */
    @Override
    public Mat getIntermediateOutput(int step)
    {
        return openCvPipeline.getIntermediateOutput(step);
    }   //getIntermediateOutput

}   //class TrcOpenCvDetector
