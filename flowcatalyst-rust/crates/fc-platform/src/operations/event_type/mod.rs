//! Event Type Operations
//!
//! Use cases for managing event types.

mod create;
mod events;

pub use create::{CreateEventTypeCommand, CreateEventTypeUseCase};
pub use events::EventTypeCreated;
