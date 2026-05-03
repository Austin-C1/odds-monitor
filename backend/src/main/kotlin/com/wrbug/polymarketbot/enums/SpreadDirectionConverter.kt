package com.wrbug.polymarketbot.enums

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

@Converter(autoApply = false)
class SpreadDirectionConverter : AttributeConverter<SpreadDirection, Int> {
    
    override fun convertToDatabaseColumn(attribute: SpreadDirection?): Int {
        return attribute?.value ?: SpreadDirection.MIN.value
    }
    
    override fun convertToEntityAttribute(dbData: Int?): SpreadDirection {
        return SpreadDirection.fromValueOrDefault(dbData)
    }
}
