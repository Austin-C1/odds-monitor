package com.wrbug.polymarketbot.enums

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

@Converter(autoApply = false)
class SpreadModeConverter : AttributeConverter<SpreadMode, Int> {
    
    override fun convertToDatabaseColumn(attribute: SpreadMode?): Int {
        return attribute?.value ?: SpreadMode.NONE.value
    }
    
    override fun convertToEntityAttribute(dbData: Int?): SpreadMode {
        return SpreadMode.fromValueOrDefault(dbData)
    }
}
