package com.veeva.vault.custom.udc;

import com.veeva.vault.sdk.api.core.*;
import com.veeva.vault.sdk.api.data.Record;
import com.veeva.vault.sdk.api.data.RecordBatchSaveRequest;
import com.veeva.vault.sdk.api.data.RecordService;
import com.veeva.vault.sdk.api.query.Query;
import com.veeva.vault.sdk.api.query.QueryExecutionRequest;
import com.veeva.vault.sdk.api.query.QueryExecutionResponse;
import com.veeva.vault.sdk.api.query.QueryService;
import com.veeva.vault.sdk.api.token.TokenRequest;
import com.veeva.vault.sdk.api.token.TokenService;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;


/**
 * ProductSearchServiceImplementation implements ProductSearchService
 */
@UserDefinedServiceInfo
public class ProductSearchUserDefinedServiceImplementation implements ProductSearchUserDefinedService {
    /*
     * The doesProductExist Method checks whether the provided part name has a matching part record.
     * @param products; a list of string values representing the product names.
     * @param manufacturers; a list of string values representing the manufacturer names.
     * @returns BicyclePartDataMap; a map containing the bicycle part name as key and an BicyclePartData object as value.
     */
    public Map<String, BicyclePartData> doesProductExist(Map<String, String> productAndManufacturerMap) {
        /*
         * Get an instance of the Query Service used to find if the part exists.
         * The Query service is used to execute VQL queries to retrieve documents or object record.
         * VQL is a Structured Query Language(SQL) like querying language used to access document or object records.
         * More information about VQL Queries can be found here: https://developer.veevavault.com/vql/#introduction-to-vault-queries
         * Query Service Java Doc can be found here: https://repo.veevavault.com/javadoc/vault-sdk-api/26.1.0/docs/api/index.html
         */
        QueryService queryService = ServiceLocator.locate(QueryService.class);

        /*
         * Get an instance of the Log Service used to log errors, exceptions and resource usage.
         * Log Service Java Doc can be found here: https://repo.veevavault.com/javadoc/vault-sdk-api/26.1.0/docs/api/index.html
         */
        LogService logService = ServiceLocator.locate(LogService.class);

        // Supply the product and manufacturer names through a TokenRequest instead of concatenating
        // them into the query string, and build the query with newQueryBuilder() rather than a String.
        // A CONTAINS filter resolves a List token into a properly quoted, comma-separated value list.
        TokenService tokenService = ServiceLocator.locate(TokenService.class);

        List<String> productNames = VaultCollections.newList();
        productNames.addAll(productAndManufacturerMap.keySet());
        List<String> manufacturerNames = VaultCollections.newList();
        manufacturerNames.addAll(productAndManufacturerMap.values());

        TokenRequest tokenRequest = tokenService.newTokenRequestBuilder()
                .withValue("Custom.product_names", productNames)
                .withValue("Custom.manufacturer_names", manufacturerNames)
                .build();

        Query partAndManufacturerQuery = queryService.newQueryBuilder()
                .withSelect(VaultCollections.asList("id", "name__v", "quantity__c"))
                .withFrom("bicycle_part__c")
                .withWhere("caseinsensitive(name__v) CONTAINS (${Custom.product_names}) AND caseinsensitive(bicycle_part_manufacturer__cr.name__v) CONTAINS (${Custom.manufacturer_names})")
                .build();

        // Create a map to hold our results.
        @SuppressWarnings("unchecked")
        Map<String, BicyclePartData> BicyclePartDataMap = VaultCollections.newMap();

        // Running our query and populating the results map via callback.
        QueryExecutionRequest queryRequest = queryService.newQueryExecutionRequestBuilder()
                .withQuery(partAndManufacturerQuery)
                .withTokenRequest(tokenRequest)
                .build();
        queryService.query(queryRequest)
                .onSuccess(queryResponse -> {
                    // If the query results count is greater than 0, then the query returned result
                    if(queryResponse.getResultCount() > 0) {
                        // Iterate over query results, get the id, name and quantity and add to BicyclePartData map.
                        queryResponse.streamResults().forEach(queryResult -> {

                            // Get id, name and quantity
                            String id = queryResult.getValue("id", ValueType.STRING);
                            String name = queryResult.getValue("name__v", ValueType.STRING);
                            BigDecimal quantity = queryResult.getValue("quantity__c", ValueType.NUMBER);

                            // Add to map with key as name and BicyclePartData object as value.
                            BicyclePartDataMap.put(name, new BicyclePartData(name, id != null, id, quantity));
                        });
                    }
                })
                .execute();

        // Log the memory used in the method.
        logService.logResourceUsage("Memory Usage In doesProductExist: ");

        if(!BicyclePartDataMap.isEmpty()) {
            // Return the results.
            return BicyclePartDataMap;
        }

        // Return null as no results were found.
        return null;
    }


    /*
     * The reduceProductQuantity Method reduces the bicycle part object record quantity by the amount specified in the bicycle part order record.
     * @param BicyclePartData; a collection of BicyclePartData objects that contain the bicycle part name, id, quantity.
     * @param orderQuantityMap; a map of the bicycle part name and bicycle part order quantity.
     */
    public void reduceProductQuantity(Collection<BicyclePartData> BicyclePartData, Map<String, BigDecimal> orderQuantityMap) {

        /*
         * Get an instance of the Record Service used to update the bicycle part order record.
         * The Record service can be used to create, update or delete object records.
         * More information about the Record Service can be found here:
         * https://repo.veevavault.com/javadoc/vault-sdk-api/26.1.0/docs/api/com/veeva/vault/sdk/api/data/RecordService.html
         */
        RecordService recordService = ServiceLocator.locate(RecordService.class);

        /*
         * Get an instance of the Log Service used to log errors, exceptions and resource usage.
         * Log Service Java Doc can be found here: https://repo.veevavault.com/javadoc/vault-sdk-api/26.1.0/docs/api/index.html
         */
        LogService logService = ServiceLocator.locate(LogService.class);

        logService.logResourceUsage("In reduceProductQuantity method");

        // Create a list to hold the records that we will update so that they can be bulk saved.
        @SuppressWarnings("unchecked")
        List<Record> updatedRecords = VaultCollections.newList();

        // Iterate over BicyclePartData objects which hold the bicycle part name, record id, and order quantity
        for(BicyclePartData currentBicyclePartData : BicyclePartData) {

            // Check if the record was found before continuing
            if(currentBicyclePartData.getIdExists()) {

                // Instantiate a record with the id that was found before and stored in the BicyclePartData object
                Record bicyclePartRecord = recordService.newRecordWithId("bicycle_part__c", currentBicyclePartData.getId());

                // Get the bicycle part quantity
                BigDecimal bicyclePartQuantity = currentBicyclePartData.getQuantity();

                // Get the bicycle part order quantity
                BigDecimal orderQuantity = orderQuantityMap.get(currentBicyclePartData.getBicyclePartName());

                if(bicyclePartQuantity != null && orderQuantity != null) {

                    // If the order quantity is less than or equal to the part quantity, then set the new quantity and add to the updateRecords list
                    if(bicyclePartQuantity.compareTo(orderQuantity) >= 0) { // Will be 0 if equal, 1 if bicyclePartQuantity is greater
                        BigDecimal updatedQuantity = bicyclePartQuantity.subtract(orderQuantity);
                        bicyclePartRecord.setValue("quantity__c", updatedQuantity);

                        updatedRecords.add(bicyclePartRecord);
                    } else {
                        throw new RollbackException("INVALID_ARGUMENT", "Order Quantity is greater than bicycle part quantity");
                    }
                } else {
                    // If the bicycle part quantity or the order quantity is null then throw an error.
                    throw new RollbackException("INVALID_ARGUMENT", "Quantity not found");
                }
            }
        }

        // Bulk save updated records
        recordService.batchSaveRecords(recordService.newRecordBatchSaveRequestBuilder()
                .withRecords(updatedRecords)
                .build()).onErrors(batchOperationErrors -> {
            // Throw errors
            batchOperationErrors.stream().findFirst().ifPresent(error -> {

                // Get the error message
                String errMsg = error.getError().getMessage();

                // Get which record the error occurred on
                int errPosition = error.getInputPosition();
                String name = updatedRecords.get(errPosition).getValue("name__v", ValueType.STRING);

                // Throw error
                throw new RollbackException("OPERATION_NOT_ALLOWED", "Unable to save: " + name +
                        " because of " + errMsg);
            });
        }).execute();

        logService.logResourceUsage("Exiting from reduceProductQuantity method");
    }

}
