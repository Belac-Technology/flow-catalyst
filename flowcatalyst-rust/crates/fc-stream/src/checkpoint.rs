use async_trait::async_trait;
use mongodb::bson::Document;
use anyhow::Result;

#[async_trait]
pub trait CheckpointStore: Send + Sync {
    async fn get_checkpoint(&self, key: &str) -> Result<Option<Document>>;
    async fn save_checkpoint(&self, key: &str, token: Document) -> Result<()>;
}

pub struct MongoCheckpointStore {
    collection: mongodb::Collection<Document>,
}

impl MongoCheckpointStore {
    pub fn new(client: mongodb::Client, db_name: &str, collection_name: &str) -> Self {
        let db = client.database(db_name);
        Self {
            collection: db.collection(collection_name),
        }
    }
}

#[async_trait]
impl CheckpointStore for MongoCheckpointStore {
    async fn get_checkpoint(&self, key: &str) -> Result<Option<Document>> {
        let filter = mongodb::bson::doc! { "_id": key };
        let doc = self.collection.find_one(filter, None).await?;
        Ok(doc.and_then(|d| d.get_document("token").ok().cloned()))
    }

    async fn save_checkpoint(&self, key: &str, token: Document) -> Result<()> {
        let filter = mongodb::bson::doc! { "_id": key };
        let update = mongodb::bson::doc! { 
            "$set": { "token": token, "updated_at": mongodb::bson::DateTime::now() } 
        };
        let options = mongodb::options::UpdateOptions::builder().upsert(true).build();
        
        self.collection.update_one(filter, update, options).await?;
        Ok(())
    }
}
