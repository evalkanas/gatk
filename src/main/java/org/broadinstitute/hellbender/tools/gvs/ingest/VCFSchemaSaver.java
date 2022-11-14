package org.broadinstitute.hellbender.tools.gvs.ingest;

import com.google.cloud.bigquery.storage.v1beta2.TableName;
import com.google.cloud.bigquery.storage.v1beta2.TableSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class VCFSchemaSaver {
    static final Logger logger = LogManager.getLogger(VCFSchemaSaver.class);

    private final String projectID;
    private final String datasetName;
    private final String sampleSchemaTableName;
    private final TableName sampleSchemaTable;

    public VCFSchemaSaver(String projectID, String datasetName, String sampleSchemaTableName) {
        this.projectID = projectID;
        this.datasetName = datasetName;
        this.sampleSchemaTableName = sampleSchemaTableName;
        this.sampleSchemaTable = TableName.of(projectID, datasetName, sampleSchemaTableName);
    }

    public TableSchema getSampleSchemaTableSchema() {
        TableSchema.Builder builder = TableSchema.newBuilder();
        /*
        builder.addFields(
                TableFieldSchema.newBuilder().setName(SchemaUtils.SAMPLE_ID_FIELD_NAME).setType(TableFieldSchema.Type.INT64).setMode(TableFieldSchema.Mode.REQUIRED).build()
        );
        builder.addFields(
                TableFieldSchema.newBuilder().setName(SchemaUtils.LOAD_STATUS_FIELD_NAME).setType(TableFieldSchema.Type.STRING).setMode(TableFieldSchema.Mode.REQUIRED).build()
        );
        builder.addFields(
                TableFieldSchema.newBuilder().setName(SchemaUtils.LOAD_STATUS_EVENT_TIMESTAMP_NAME).setType(TableFieldSchema.Type.TIMESTAMP).setMode(TableFieldSchema.Mode.REQUIRED).build()
        );
         */
        return builder.build();
    }

}
