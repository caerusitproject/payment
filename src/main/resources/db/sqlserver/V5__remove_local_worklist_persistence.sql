/*
 * Worklist task records are owned by the external Worklist Service.
 * This migration removes the old local fallback table and adds the manual task reference.
 */
IF OBJECT_ID('dbo.c3_pymnt_worklist_item', 'U') IS NOT NULL
    DROP TABLE dbo.c3_pymnt_worklist_item;
GO

IF COL_LENGTH('dbo.c3_pymnt_txn', 'manual_wkl_task_id') IS NULL
    ALTER TABLE dbo.c3_pymnt_txn ADD manual_wkl_task_id BIGINT NULL;
GO

IF COL_LENGTH('dbo.c3_pymnt_txn', 'manual_wkl_task_created_ts') IS NULL
    ALTER TABLE dbo.c3_pymnt_txn ADD manual_wkl_task_created_ts DATETIME2(3) NULL;
GO
