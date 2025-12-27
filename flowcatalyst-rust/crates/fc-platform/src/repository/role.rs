//! Role Repository

use mongodb::{Collection, Database, bson::doc};
use futures::TryStreamExt;
use crate::domain::{AuthRole, RoleSource};
use crate::error::Result;

pub struct RoleRepository {
    collection: Collection<AuthRole>,
}

impl RoleRepository {
    pub fn new(db: &Database) -> Self {
        Self {
            collection: db.collection("roles"),
        }
    }

    pub async fn insert(&self, role: &AuthRole) -> Result<()> {
        self.collection.insert_one(role, None).await?;
        Ok(())
    }

    pub async fn find_by_id(&self, id: &str) -> Result<Option<AuthRole>> {
        Ok(self.collection.find_one(doc! { "_id": id }, None).await?)
    }

    pub async fn find_by_code(&self, code: &str) -> Result<Option<AuthRole>> {
        Ok(self.collection.find_one(doc! { "code": code }, None).await?)
    }

    pub async fn find_all(&self) -> Result<Vec<AuthRole>> {
        let cursor = self.collection.find(doc! {}, None).await?;
        Ok(cursor.try_collect().await?)
    }

    pub async fn find_by_application(&self, application_code: &str) -> Result<Vec<AuthRole>> {
        let cursor = self.collection
            .find(doc! { "applicationCode": application_code }, None)
            .await?;
        Ok(cursor.try_collect().await?)
    }

    pub async fn find_by_source(&self, source: RoleSource) -> Result<Vec<AuthRole>> {
        let source_str = serde_json::to_string(&source)
            .unwrap_or_default()
            .trim_matches('"')
            .to_string();
        let cursor = self.collection
            .find(doc! { "source": source_str }, None)
            .await?;
        Ok(cursor.try_collect().await?)
    }

    pub async fn find_client_managed(&self) -> Result<Vec<AuthRole>> {
        let cursor = self.collection
            .find(doc! { "clientManaged": true }, None)
            .await?;
        Ok(cursor.try_collect().await?)
    }

    pub async fn find_by_codes(&self, codes: &[String]) -> Result<Vec<AuthRole>> {
        let cursor = self.collection
            .find(doc! { "code": { "$in": codes } }, None)
            .await?;
        Ok(cursor.try_collect().await?)
    }

    pub async fn find_with_permission(&self, permission: &str) -> Result<Vec<AuthRole>> {
        let cursor = self.collection
            .find(doc! { "permissions": permission }, None)
            .await?;
        Ok(cursor.try_collect().await?)
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

    pub async fn update(&self, role: &AuthRole) -> Result<()> {
        self.collection
            .replace_one(doc! { "_id": &role.id }, role, None)
            .await?;
        Ok(())
    }

    pub async fn delete(&self, id: &str) -> Result<bool> {
        let result = self.collection.delete_one(doc! { "_id": id }, None).await?;
        Ok(result.deleted_count > 0)
    }
}
