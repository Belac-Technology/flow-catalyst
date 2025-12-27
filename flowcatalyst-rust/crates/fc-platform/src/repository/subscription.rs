//! Subscription Repository

use mongodb::{Collection, Database, bson::doc};
use futures::TryStreamExt;
use crate::domain::Subscription;
use crate::error::Result;

pub struct SubscriptionRepository {
    collection: Collection<Subscription>,
}

impl SubscriptionRepository {
    pub fn new(db: &Database) -> Self {
        Self {
            collection: db.collection("subscriptions"),
        }
    }

    pub async fn insert(&self, subscription: &Subscription) -> Result<()> {
        self.collection.insert_one(subscription, None).await?;
        Ok(())
    }

    pub async fn find_by_id(&self, id: &str) -> Result<Option<Subscription>> {
        Ok(self.collection.find_one(doc! { "_id": id }, None).await?)
    }

    pub async fn find_by_code(&self, code: &str) -> Result<Option<Subscription>> {
        Ok(self.collection.find_one(doc! { "code": code }, None).await?)
    }

    pub async fn find_by_code_and_client(&self, code: &str, client_id: Option<&str>) -> Result<Option<Subscription>> {
        let filter = match client_id {
            Some(id) => doc! { "code": code, "$or": [{ "clientId": id }, { "clientId": null }] },
            None => doc! { "code": code, "clientId": null },
        };
        Ok(self.collection.find_one(filter, None).await?)
    }

    pub async fn find_active(&self) -> Result<Vec<Subscription>> {
        let cursor = self.collection
            .find(doc! { "status": "ACTIVE" }, None)
            .await?;
        Ok(cursor.try_collect().await?)
    }

    pub async fn find_by_client(&self, client_id: Option<&str>) -> Result<Vec<Subscription>> {
        let filter = match client_id {
            Some(id) => doc! { "$or": [{ "clientId": id }, { "clientId": null }] },
            None => doc! { "clientId": null },
        };
        let cursor = self.collection.find(filter, None).await?;
        Ok(cursor.try_collect().await?)
    }

    /// Find subscriptions that match a given event type code
    /// This is a candidate query - actual matching requires Subscription::matches_event_type
    pub async fn find_active_by_event_type(&self, event_type_code: &str) -> Result<Vec<Subscription>> {
        // Get the application prefix (first segment)
        let prefix = event_type_code.split(':').next().unwrap_or("");

        // Query for active subscriptions that might match
        // The actual wildcard matching is done in memory
        let cursor = self.collection
            .find(doc! {
                "status": "ACTIVE",
                "eventTypes.eventTypeCode": {
                    "$regex": format!("^{}:", regex::escape(prefix))
                }
            }, None)
            .await?;

        let subscriptions: Vec<Subscription> = cursor.try_collect().await?;

        // Filter in memory for exact matches including wildcards
        Ok(subscriptions.into_iter()
            .filter(|s| s.matches_event_type(event_type_code))
            .collect())
    }

    /// Find matching subscriptions for an event
    pub async fn find_matching(&self, event_type_code: &str, client_id: Option<&str>) -> Result<Vec<Subscription>> {
        let subscriptions = self.find_active_by_event_type(event_type_code).await?;

        Ok(subscriptions.into_iter()
            .filter(|s| s.matches_client(client_id))
            .collect())
    }

    pub async fn find_by_dispatch_pool(&self, pool_id: &str) -> Result<Vec<Subscription>> {
        let cursor = self.collection
            .find(doc! { "dispatchPoolId": pool_id }, None)
            .await?;
        Ok(cursor.try_collect().await?)
    }

    pub async fn find_by_service_account(&self, service_account_id: &str) -> Result<Vec<Subscription>> {
        let cursor = self.collection
            .find(doc! { "serviceAccountId": service_account_id }, None)
            .await?;
        Ok(cursor.try_collect().await?)
    }

    pub async fn update(&self, subscription: &Subscription) -> Result<()> {
        self.collection
            .replace_one(doc! { "_id": &subscription.id }, subscription, None)
            .await?;
        Ok(())
    }

    pub async fn delete(&self, id: &str) -> Result<bool> {
        let result = self.collection.delete_one(doc! { "_id": id }, None).await?;
        Ok(result.deleted_count > 0)
    }
}
