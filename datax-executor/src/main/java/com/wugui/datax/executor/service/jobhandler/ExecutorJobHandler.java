package com.wugui.datax.executor.service.jobhandler;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import com.wugui.datatx.core.biz.model.HandleProcessCallbackParam;
import com.wugui.datatx.core.biz.model.ReturnT;
import com.wugui.datatx.core.biz.model.TriggerParam;
import com.wugui.datatx.core.handler.IJobHandler;
import com.wugui.datatx.core.handler.annotation.JobHandler;
import com.wugui.datatx.core.log.JobLogger;
import com.wugui.datatx.core.thread.ProcessCallbackThread;
import com.wugui.datatx.core.util.ProcessUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * DataX任务运行
 *
 * @author jingwk 2019-11-16
 */

@JobHandler(value = "executorJobHandler")
@Component
public class ExecutorJobHandler extends IJobHandler {

    @Value("${datax.executor.jsonpath}")
    private String jsonpath;

    @Value("${datax.pypath}")
    private String dataXPyPath;

    @Override
    public ReturnT<String> executeDataX(TriggerParam tgParam) throws Exception {

        int exitValue = -1;
        Thread inputThread = null;
        Thread errThread = null;
        String tmpFilePath;
        //生成Json临时文件
        tmpFilePath = generateTemJsonFile(tgParam.getJobJson());
        try {
            String doc = buildStartCommand(tgParam.getJvmParam(), tgParam.getTriggerTime(), tgParam.getReplaceParam(), tgParam.getStartTime());
            JobLogger.log("------------------命令参数: " + doc);
            // command process
            //"--loglevel=debug"
            List<String> cmdarray = new ArrayList<>();
            cmdarray.add("python");
            cmdarray.add(dataXPyPath);
            if(StringUtils.isNotBlank(doc)){
                cmdarray.add(doc.replaceAll(DataxOption.SPLIT_SPACE, DataxOption.TRANSFORM_SPLIT_SPACE));
            }
            cmdarray.add(tmpFilePath);
            String[] cmdarrayFinal = cmdarray.toArray(new String[cmdarray.size()]);
            final Process process = Runtime.getRuntime().exec(cmdarrayFinal);
            String processId = ProcessUtil.getProcessId(process);
            JobLogger.log("------------------DataX运行进程Id: " + processId);
            jobTmpFiles.put(processId, tmpFilePath);
            //更新任务进程Id
            ProcessCallbackThread.pushCallBack(new HandleProcessCallbackParam(tgParam.getLogId(), tgParam.getLogDateTime(), processId));
            // log-thread
            inputThread = new Thread(() -> {
                try {
                    reader(new BufferedInputStream(process.getInputStream()));
                } catch (IOException e) {
                    JobLogger.log(e);
                }
            });
            errThread = new Thread(() -> {
                try {
                    reader(new BufferedInputStream(process.getErrorStream()));
                } catch (IOException e) {
                    JobLogger.log(e);
                }
            });
            inputThread.start();
            errThread.start();
            // process-wait
            exitValue = process.waitFor();      // exit code: 0=success, 1=error
            // log-thread join
            inputThread.join();
            errThread.join();
        } catch (Exception e) {
            JobLogger.log(e);
        } finally {
            if (inputThread != null && inputThread.isAlive()) {
                inputThread.interrupt();
            }
            if (errThread != null && errThread.isAlive()) {
                errThread.interrupt();
            }
            //  删除临时文件
            if (FileUtil.exist(tmpFilePath)) {
                FileUtil.del(new File(tmpFilePath));
            }
        }
        if (exitValue == 0) {
            return IJobHandler.SUCCESS;
        } else {
            return new ReturnT<>(IJobHandler.FAIL.getCode(), "command exit value(" + exitValue + ") is failed");
        }
    }

    /**
     * 数据流reader（Input自动关闭，Output不处理）
     *
     * @param inputStream
     * @throws IOException
     */
    private static void reader(InputStream inputStream) throws IOException {
        try {
            BufferedReader reader=new BufferedReader(new InputStreamReader(inputStream));
            String line;
            while ((line = reader.readLine()) != null) {
                JobLogger.log(line);
            }
            reader.close();
            inputStream = null;
        } finally {
            if (inputStream != null) {
                inputStream.close();
            }
        }
    }

    private String buildStartCommand(String jvmParam, Date triggerTime, String replaceParam, Date startTime) {
        StringBuilder doc = new StringBuilder();
        if (StringUtils.isNotBlank(jvmParam)) {
            doc.append(DataxOption.JVM_CM).append(DataxOption.TRANSFORM_QUOTES).append(jvmParam).append(DataxOption.TRANSFORM_QUOTES);
        }
        long tgSecondTime = triggerTime.getTime() / 1000;
        if (StringUtils.isNotBlank(replaceParam)) {
            long lastTime = startTime.getTime() / 1000;
            if (doc.indexOf(DataxOption.JVM_CM) != -1) doc.append(DataxOption.SPLIT_SPACE);
            doc.append(DataxOption.PARAMS_CM).append(DataxOption.TRANSFORM_QUOTES).append(String.format(replaceParam, lastTime, tgSecondTime)).append(DataxOption.TRANSFORM_QUOTES);
        }
        return doc.toString();
    }

    private String generateTemJsonFile(String jobJson) {
        String tmpFilePath;
        if (!FileUtil.exist(jsonpath)) FileUtil.mkdir(jsonpath);
        tmpFilePath = jsonpath + "jobTmp-" + IdUtil.simpleUUID() + ".conf";
        // 根据json写入到临时本地文件
        try (PrintWriter writer = new PrintWriter(tmpFilePath, "UTF-8")) {
            writer.println(jobJson);
        } catch (FileNotFoundException | UnsupportedEncodingException e) {
            JobLogger.log("JSON 临时文件写入异常：" + e.getMessage());
        }
        return tmpFilePath;
    }
}
