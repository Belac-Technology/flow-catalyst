//! Client Repository

use mongodb::{Collection, Database, bson::doc};
use futures::TryStreamExt;
use crate::domain::{Client, ClientStatus};
use crate::error::Result;

pub struct ClientRepository {
    collection: Collection<Client>,
}

impl ClientRepository {
    pub fn new(db: &Database) -> Self {
        Self {
            collection: db.collection("clients"),
        }
    }

    pub async fn insert(&self, client: &Client) -> Result<()> {
        self.collection.insert_one(client, None).await?;
        Ok(())
    }

    pub async fn find_by_id(&self, id: &str) -> Result<Option<Client>> {
        Ok(self.collection.find_one(doc! { "_id": id }, None).await?)
    }

    pub async fn find_by_identifier(&self, identifier: &str) -> Result<Option<Client>> {
        Ok(self.collection.find_one(doc! { "identifier": identifier }, None).await?)
    }

    pub async fn find_active(&self) -> Result<Vec<Client>> {
        let cursor = self.collection
            .find(doc! { "status": "ACTIVE" }, None)
            .await?;
        Ok(cursor.try_collect().await?)
    }

    pub async fn find_all(&self) -> Result<Vec<Client>> {
        let cursor = self.collection.find(doc! {}, None).await?;
        Ok(cursor.try_collect().await?)
    }

    pub async fn find_by_status(&self, status: ClientStatus) -> Result<Vec<Client>> {
        let status_str = serde_json::to_string(&status)
            .unwrap_or_default()
            .trim_matches('"')
            .to_string();
        let cursor = self.collection
            .find(doc! { "status": status_str }, None)
            .await?;
        Ok(cursor.try_collect().await?)
    }

    pub async fn find_by_ids(&self, ids: &[String]) -> Result<Vec<Client>> {
        let cursor = self.collection
            .find(doc! { "_id": { "$in": ids } }, None)
            .await?;
        Ok(cursor.try_collect().await?)
    }

    pub async fn exists(&self, id: &str) -> Result<bool> {
        let count = self.collection
            .count_documents(doc! { "_id": id }, None)
            .await?;
        Ok(count > 0)
    }

    pub async fn exists_by_identifier(&self, identifier: &str) -> Result<bool> {
        let count = self.collection
            .count_documents(doc! { "identifier": identifier }, None)
            .await?;
        Ok(count > 0)
    }

    pub async fn update(&self, client: &Client) -> Result<()> {
        self.collection
            .replace_one(doc! { "_id": &client.id }, client, None)
            .await?;
        Ok(())
    }

    pub async fn delete(&self, id: &str) -> Result<bool> {
        let result = self.collection.delete_one(doc! { "_id": id }, None).await?;
        Ok(result.deleted_count > 0)
    }
}
