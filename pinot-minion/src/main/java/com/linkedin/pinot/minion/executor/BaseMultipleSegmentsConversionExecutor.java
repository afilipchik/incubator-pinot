package com.linkedin.pinot.minion.executor;

import com.google.common.base.Preconditions;
import com.linkedin.pinot.common.config.PinotTaskConfig;
import com.linkedin.pinot.common.exception.HttpErrorStatusException;
import com.linkedin.pinot.common.metadata.segment.SegmentZKMetadataCustomMapModifier;
import com.linkedin.pinot.common.segment.fetcher.SegmentFetcherFactory;
import com.linkedin.pinot.common.utils.ClientSSLContextGenerator;
import com.linkedin.pinot.common.utils.CommonConstants;
import com.linkedin.pinot.common.utils.FileUploadDownloadClient;
import com.linkedin.pinot.common.utils.SimpleHttpResponse;
import com.linkedin.pinot.common.utils.TarGzCompressionUtils;
import com.linkedin.pinot.common.utils.retry.RetryPolicies;
import com.linkedin.pinot.common.utils.retry.RetryPolicy;
import com.linkedin.pinot.core.common.MinionConstants;
import com.linkedin.pinot.minion.exception.TaskCancelledException;
import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.net.ssl.SSLContext;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.io.FileUtils;
import org.apache.http.Header;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpStatus;
import org.apache.http.NameValuePair;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicNameValuePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public abstract class BaseMultipleSegmentsConversionExecutor extends BaseTaskExecutor {

  private static final Logger LOGGER = LoggerFactory.getLogger(BaseMultipleSegmentsConversionExecutor.class);

  private static final int DEFAULT_MAX_NUM_ATTEMPTS = 5;
  private static final long DEFAULT_INITIAL_RETRY_DELAY_MS = 1000L; // 1 second
  private static final double DEFAULT_RETRY_SCALE_FACTOR = 2.0;
  private static final String HTTPS_PROTOCOL = "https";
  private static final String CONFIG_OF_CONTROLLER_HTTPS_ENABLED = "enabled";

  private static SSLContext _sslContext;

  public static void init(Configuration uploaderConfig) {
    Configuration httpsConfig = uploaderConfig.subset(HTTPS_PROTOCOL);
    if (httpsConfig.getBoolean(CONFIG_OF_CONTROLLER_HTTPS_ENABLED, false)) {
      _sslContext = new ClientSSLContextGenerator(httpsConfig.subset(CommonConstants.PREFIX_OF_SSL_SUBSET)).generate();
    }
  }

  /**
   * Converts the segment based on the given {@link PinotTaskConfig}.
   *
   * @param pinotTaskConfig Task config
   * @param originalIndexDir Index directory for the original segment
   * @param workingDir Working directory for the converted segment
   * @return Segment conversion result
   * @throws Exception
   */
  protected abstract List<SegmentConversionResult> convert(@Nonnull PinotTaskConfig pinotTaskConfig,
      @Nonnull List<File> originalIndexDir, @Nonnull File workingDir) throws Exception;

  /**
   * Returns the segment ZK metadata custom map modifier.
   *
   * @return Segment ZK metadata custom map modifier
   */
  protected abstract SegmentZKMetadataCustomMapModifier getSegmentZKMetadataCustomMapModifier();

  @Override
  public List<SegmentConversionResult> executeTask(@Nonnull PinotTaskConfig pinotTaskConfig) throws Exception {
    String taskType = pinotTaskConfig.getTaskType();
    Map<String, String> configs = pinotTaskConfig.getConfigs();
    final String tableNameWithType = configs.get(MinionConstants.TABLE_NAME_KEY);
    final String segmentName = configs.get(MinionConstants.SEGMENT_NAME_KEY);
    String downloadURL = configs.get(MinionConstants.DOWNLOAD_URL_KEY);
    final String uploadURL = configs.get(MinionConstants.UPLOAD_URL_KEY);
    String originalSegmentCrc = configs.get(MinionConstants.ORIGINAL_SEGMENT_CRC_KEY);

    LOGGER.info("Start executing {} on table: {}, segment: {} with downloadURL: {}, uploadURL: {}", taskType,
        tableNameWithType, segmentName, downloadURL, uploadURL);

    String[] downloadURLs = downloadURL.split(",");

    File tempDataDir = new File(new File(MINION_CONTEXT.getDataDir(), taskType), "tmp-" + System.nanoTime());
    Preconditions.checkState(tempDataDir.mkdirs());
    try {

      // Download the tarred segment files and un-tar them
      List<File> inputSegmentFiles = new ArrayList<>();
      for (int i = 0; i < downloadURLs.length; i++) {
        // Download the segment files
        File tarredSegmentFile = new File(tempDataDir, "tarredSegmentFile_" + i);
        SegmentFetcherFactory.getInstance()
            .getSegmentFetcherBasedOnURI(downloadURLs[i])
            .fetchSegmentToLocal(downloadURLs[i], tarredSegmentFile);

        // Un-tar the segment files
        File segmentDir = new File(tempDataDir, "segmentDir_" + i);
        TarGzCompressionUtils.unTar(tarredSegmentFile, segmentDir);
        File[] files = segmentDir.listFiles();
        Preconditions.checkState(files != null && files.length == 1);
        File indexDir = files[0];
        inputSegmentFiles.add(indexDir);
      }

      // Convert the segment
      File workingDir = new File(tempDataDir, "workingDir");
      Preconditions.checkState(workingDir.mkdir());
      List<SegmentConversionResult> segmentConversionResults = convert(pinotTaskConfig, inputSegmentFiles, workingDir);
      List<File> tarredSegmentFiles = new ArrayList<>();

      // Tar the converted segment
      for (int i = 0; i < segmentConversionResults.size(); i++) {
        SegmentConversionResult segmentConversionResult = segmentConversionResults.get(i);
        File convertedIndexDir = segmentConversionResult.getFile();

        File convertedTarredSegmentDir = new File(tempDataDir, "convertedTarredSegmentDir");
        Preconditions.checkState(convertedTarredSegmentDir.mkdir());
        final File convertedTarredSegmentFile = new File(
            TarGzCompressionUtils.createTarGzOfDirectory(convertedIndexDir.getPath(),
                new File(convertedTarredSegmentDir, segmentConversionResult.getSegmentName()).getPath()));

        tarredSegmentFiles.add(convertedTarredSegmentFile);
      }

      // Check whether the task get cancelled before uploading the segment
      if (_cancelled) {
        LOGGER.info("{} on table: {}, segment: {} got cancelled", taskType, tableNameWithType, segmentName);
        throw new TaskCancelledException(
            taskType + " on table: " + tableNameWithType + ", segment: " + segmentName + " got cancelled");
      }

      for (int i = 0; i < tarredSegmentFiles.size(); i++) {
//        // Set original segment CRC into HTTP IF-MATCH header to check whether the original segment get refreshed, so that
//        // the newer segment won't get override
//        Header ifMatchHeader = new BasicHeader(HttpHeaders.IF_MATCH, originalSegmentCrc);
//        // Set segment ZK metadata custom map modifier into HTTP header to modify the segment ZK metadata
//        // NOTE: even segment is not changed, still need to upload the segment to update the segment ZK metadata so that
//        // segment will not be submitted again
//        SegmentZKMetadataCustomMapModifier segmentZKMetadataCustomMapModifier = getSegmentZKMetadataCustomMapModifier();
//        Header segmentZKMetadataCustomMapModifierHeader =
//            new BasicHeader(FileUploadDownloadClient.CustomHeaders.SEGMENT_ZK_METADATA_CUSTOM_MAP_MODIFIER,
//                segmentZKMetadataCustomMapModifier.toJsonString());
//        final List<Header> httpHeaders = Arrays.asList(ifMatchHeader, segmentZKMetadataCustomMapModifierHeader);

        File convertedTarredSegmentFile = tarredSegmentFiles.get(i);
        SegmentConversionResult segmentConversionResult = segmentConversionResults.get(i);

        final String resultSegmentName = segmentConversionResult.getSegmentName();


        // Set query parameters
        final List<NameValuePair> parameters = Collections.singletonList(
            new BasicNameValuePair(FileUploadDownloadClient.QueryParameters.ENABLE_PARALLEL_PUSH_PROTECTION, "true"));

        String maxNumAttemptsConfig = configs.get(MinionConstants.MAX_NUM_ATTEMPTS_KEY);
        int maxNumAttempts =
            maxNumAttemptsConfig != null ? Integer.parseInt(maxNumAttemptsConfig) : DEFAULT_MAX_NUM_ATTEMPTS;
        String initialRetryDelayMsConfig = configs.get(MinionConstants.INITIAL_RETRY_DELAY_MS_KEY);
        long initialRetryDelayMs = initialRetryDelayMsConfig != null ? Long.parseLong(initialRetryDelayMsConfig)
            : DEFAULT_INITIAL_RETRY_DELAY_MS;
        String retryScaleFactorConfig = configs.get(MinionConstants.RETRY_SCALE_FACTOR_KEY);
        double retryScaleFactor =
            retryScaleFactorConfig != null ? Double.parseDouble(retryScaleFactorConfig) : DEFAULT_RETRY_SCALE_FACTOR;
        RetryPolicy retryPolicy =
            RetryPolicies.exponentialBackoffRetryPolicy(maxNumAttempts, initialRetryDelayMs, retryScaleFactor);

        try (FileUploadDownloadClient fileUploadDownloadClient = new FileUploadDownloadClient(_sslContext)) {
          retryPolicy.attempt(() -> {
            try {
              SimpleHttpResponse response =
                  fileUploadDownloadClient.uploadSegment(new URI(uploadURL), resultSegmentName, convertedTarredSegmentFile,
                      null, parameters, FileUploadDownloadClient.DEFAULT_SOCKET_TIMEOUT_MS);
              LOGGER.info("Got response {}: {} while uploading table: {}, segment: {} with uploadURL: {}",
                  response.getStatusCode(), response.getResponse(), tableNameWithType, resultSegmentName, uploadURL);
              return true;
            } catch (HttpErrorStatusException e) {
              int statusCode = e.getStatusCode();
              if (statusCode == HttpStatus.SC_CONFLICT || statusCode >= 500) {
                // Temporary exception
                LOGGER.warn("Caught temporary exception while uploading segment: {}, will retry", resultSegmentName, e);
                return false;
              } else {
                // Permanent exception
                LOGGER.error("Caught permanent exception while uploading segment: {}, won't retry", resultSegmentName, e);
                throw e;
              }
            } catch (Exception e) {
              LOGGER.warn("Caught temporary exception while uploading segment: {}, will retry", resultSegmentName, e);
              return false;
            }
          });
        }
      }

      LOGGER.info("Done executing {} on table: {}, segment: {}", taskType, tableNameWithType, segmentName);
      return segmentConversionResults;
    } finally {
      FileUtils.deleteQuietly(tempDataDir);
    }
  }
}