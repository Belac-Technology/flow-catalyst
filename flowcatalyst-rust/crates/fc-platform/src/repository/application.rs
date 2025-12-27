//! Application Repository

use mongodb::{Collection, Database, bson::doc};
use futures::TryStreamExt;
use crate::domain::Application;
use crate::error::Result;

pub struct ApplicationRepository {
    collection: Collection<Application>,
}

impl ApplicationRepository {
    pub fn new(db: &Database) -> Self {
        Self {
            collection: db.collection("applications"),
        }
    }

    pub async fn insert(&self, application: &Application) -> Result<()> {
        self.collection.insert_one(application, None).await?;
        Ok(())
    }

    pub async fn find_by_id(&self, id: &str) -> Result<Option<Application>> {
        Ok(self.collection.find_one(doc! { "_id": id }, None).await?)
    }

    pub async fn find_by_code(&self, code: &str) -> Result<Option<Application>> {
        Ok(self.collection.find_one(doc! { "code": code }, None).await?)
    }

    pub async fn find_active(&self) -> Result<Vec<Application>> {
        let cursor = self.collection
            .find(doc! { "active": true }, None)
            .await?;
        Ok(cursor.try_collect().await?)
    }

    pub async fn find_all(&self) -> Result<Vec<Application>> {
        let cursor = self.collection.find(doc! {}, None).await?;
        Ok(cursor.try_collect().await?)
    }

    pub async fn find_applications(&self) -> Result<Vec<Application>> {
        let cursor = self.collection
            .find(doc! { "type": "APPLICATION", "active": true }, None)
            .await?;
        Ok(cursor.try_collect().await?)
    }

    pub async fn find_integrations(&self) -> Result<Vec<Application>> {
        let cursor = self.collection
            .find(doc! { "type": "INTEGRATION", "active": true }, None)
            .await?;
        Ok(cursor.try_collect().await?)
    }

    pub async fn find_by_service_account(&self, service_account_id: &str) -> Result<Option<Application>> {
        Ok(self.collection
            .find_one(doc! { "serviceAccountId": service_account_id }, None)
            .await?)
    }

    pub async fn exists(&self, id: &str) -> Result<bool> {
        let count = self.collection
            .count_documents(doc! { "_id": id }, None)
            .await?;
        Ok(count > 0)
    }

    pub async fn exists_by_code(&self, code: &str) -> Result<bool> {
        let count = self.collection
            .count_documents(doc! { "code": code }, None)
            .await?;
        Ok(count > 0)
    }

    pub async fn update(&self, application: &Application) -> Result<()> {
        self.collection
            .replace_one(doc! { "_id": &application.id }, application, None)
            .await?;
        Ok(())
    }

    pub async fn delete(&self, id: &str) -> Result<bool> {
        let result = self.collection.delete_one(doc! { "_id": id }, None).await?;
        Ok(result.deleted_count > 0)
    }
}
