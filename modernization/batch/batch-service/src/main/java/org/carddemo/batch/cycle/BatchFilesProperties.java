package org.carddemo.batch.cycle;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The cycle's chunk size (batch.md, files and tables). Where the files are is not a setting: the
 * inbound daily file is {@code <CARDDEMO_VAR_DIR>/inbound/dailytran/<businessDate>.txt}, and the
 * archive copies and the reject files are under the execution's output root
 * ({@link org.carddemo.batch.execution.OutputRoots}, batch-execution-contract.md section 4.10).
 *
 * @param chunkSize records per transaction of the chunked steps
 */
@ConfigurationProperties("carddemo.batch.files")
public record BatchFilesProperties(@DefaultValue("100") int chunkSize) {
}
