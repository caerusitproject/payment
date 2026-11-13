IF DB_ID(N'payments') IS NULL
BEGIN
    CREATE DATABASE payments;
END;
GO
