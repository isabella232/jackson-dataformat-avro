package com.fasterxml.jackson.dataformat.avro;

import java.io.IOException;

import org.apache.avro.Schema;
import org.apache.avro.Schema.Type;
import org.apache.avro.generic.*;
import org.apache.avro.io.BinaryEncoder;

import com.fasterxml.jackson.core.JsonStreamContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.dataformat.avro.ser.ArrayWriteContext;
import com.fasterxml.jackson.dataformat.avro.ser.MapWriteContext;
import com.fasterxml.jackson.dataformat.avro.ser.ObjectWriteContext;

public abstract class AvroWriteContext
    extends JsonStreamContext
{
    protected final AvroWriteContext _parent;
    
    protected final AvroGenerator _generator;
    
    protected final Schema _schema;
    
    /*
    /**********************************************************
    /* Life-cycle
    /**********************************************************
     */
    
    protected AvroWriteContext(int type, AvroWriteContext parent,
            AvroGenerator generator, Schema schema)
    {
        super();
        _type = type;
        _parent = parent;
        _generator = generator;
        _schema = schema;
    }
    
    // // // Factory methods
    
    public static AvroWriteContext createRootContext(AvroGenerator generator, Schema schema) {
        return new RootContext(generator, schema);
    }

    /**
     * Factory method called to get a placeholder context that is only
     * in place until actual schema is handed.
     */
    public static AvroWriteContext createNullContext() {
        return NullContext.instance;
    }
    
    public abstract AvroWriteContext createChildArrayContext() throws JsonMappingException;
    public abstract AvroWriteContext createChildObjectContext() throws JsonMappingException;
    
    @Override
    public final AvroWriteContext getParent() { return _parent; }
    
    @Override
    public String getCurrentName() { return null; }

    /**
     * Method that writer is to call before it writes a field name.
     *
     * @return True for Object (record) context; false for others
     */
    public boolean writeFieldName(String name) { return false; }

    public abstract void writeValue(Object value);

    /**
     * Accessor called to link data being built with resulting object.
     */
    public abstract Object rawValue();
    
    public void complete(BinaryEncoder encoder) throws IOException {
        throw new IllegalStateException("Can not be called on "+getClass().getName());
    }
    
    public boolean canClose() { return true; }

    protected abstract void appendDesc(StringBuilder sb);
    
    // // // Overridden standard methods
    
    /**
     * Overridden to provide developer writeable "JsonPath" representation
     * of the context.
     */
    @Override
    public final String toString()
    {
        StringBuilder sb = new StringBuilder(64);
        appendDesc(sb);
        return sb.toString();
    }

    // // // Shared helper methods

    protected GenericRecord _createRecord(Schema schema) throws JsonMappingException
    {
        // Quick check: if type is Union, need to find actual record type...
        //   System.err.println("Create schema for type ("+schema.getType()+"), "+schema);        
        Type type = schema.getType();
        if (type == Schema.Type.UNION) {
            Schema match = null;
            for (Schema s : schema.getTypes()) {
                if (s.getType() == Schema.Type.RECORD) {
                    if (match != null) {
                        throw new IllegalStateException("Multiple Record types, can not figure out which to use for: "
                                +schema);
                    }
                    match = s;
                }
            }
            if (match == null) {
                throw new IllegalStateException("No Record type found in union type: "+schema);
            }
            schema = match;
        } else if (type == Schema.Type.MAP) {
            throw new IllegalStateException("Should never be called for elements of type MAP");
        }
        /* 03-Mar-2014, tatu: Bit nasty, but looks like higher level code has a slightly
         *   better chance to react on these problems if we expose more refined
         *   Exception.
         */
        try {
            return new GenericData.Record(schema);
        } catch (RuntimeException e) {
            throw new JsonMappingException("Failed to create Record type from "+type, e);
        }
    }
    
    protected GenericArray<Object> _createArray(Schema schema)
    {
        if (schema.getType() == Schema.Type.UNION) {
            Schema match = null;
            for (Schema s : schema.getTypes()) {
                if (s.getType() == Schema.Type.ARRAY) {
                    if (match != null) {
                        throw new IllegalStateException("Multiple Array types, can not figure out which to use for: "
                                +schema);
                    }
                    match = s;
                }
            }
            if (match == null) {
                throw new IllegalStateException("No Array type found in union type: "+schema);
            }
            schema = match;
        }
        return new GenericData.Array<Object>(8, schema);
    }

    protected AvroWriteContext _createObjectContext(Schema schema) throws JsonMappingException
    {
        if (schema.getType() == Schema.Type.MAP) {
            return new MapWriteContext(this, _generator, schema);
        }
        return new ObjectWriteContext(this, _generator, _createRecord(schema));
    }
    
    /*
    /**********************************************************
    /* Implementations
    /**********************************************************
     */

    private final static class NullContext
        extends AvroWriteContext
    {
        public final static NullContext instance = new NullContext();
        
        private NullContext() {
            super(TYPE_ROOT, null, null, null);
        }

        @Override
        public Object rawValue() { return null; }
        
        @Override
        public final AvroWriteContext createChildArrayContext() {
            _reportError();
            return null;
        }
        
        @Override
        public final AvroWriteContext createChildObjectContext() {
            _reportError();
            return null;
        }
    
        @Override
        public void writeValue(Object value) {
            _reportError();
        }
        
        @Override
        public void appendDesc(StringBuilder sb) {
            sb.append("?");
        }

        protected void _reportError() {
            throw new IllegalStateException("Can not write Avro output without specifying Schema");
        }
    }
    
    private final static class RootContext
        extends AvroWriteContext
    {
        /**
         * We need to keep reference to the root value here.
         */
        protected GenericContainer _rootValue;
        
        protected RootContext(AvroGenerator generator, Schema schema) {
            super(TYPE_ROOT, null, generator, schema);
        }

        @Override
        public Object rawValue() { return _rootValue; }
        
        @Override
        public final AvroWriteContext createChildArrayContext() throws JsonMappingException
        {
            // verify that root type is array (or compatible)
            switch (_schema.getType()) {
            case ARRAY:
            case UNION: // maybe
                break;
            default:
                throw new IllegalStateException("Can not write START_ARRAY; schema type is "
                        +_schema.getType());
            }
            GenericArray<Object> arr = _createArray(_schema);
            _rootValue = arr;
            return new ArrayWriteContext(this, _generator, arr);
        }
        
        @Override
        public final AvroWriteContext createChildObjectContext() throws JsonMappingException
        {
            // verify that root type is record (or compatible)
            switch (_schema.getType()) {
            case RECORD:
            case UNION: // maybe
                break;
            default:
                throw new IllegalStateException("Can not write START_OBJECT; schema type is "
                        +_schema.getType());
            }
            GenericRecord rec = _createRecord(_schema);
            _rootValue = rec;
            return new ObjectWriteContext(this, _generator, rec);
        }

        @Override
        public void writeValue(Object value) {
            throw new IllegalStateException("Can not write values directly in root context, outside of Records/Arrays");
        }

        @Override
        public void complete(BinaryEncoder encoder) throws IOException
        {
            new GenericDatumWriter<GenericContainer>(_schema).write(_rootValue, encoder);
        }

        @Override
        public void appendDesc(StringBuilder sb) {
            sb.append("/");
        }
    }
}
